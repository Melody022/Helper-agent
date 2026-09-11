package com.jixiejia.agent.publish;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiPublishRequest;
import com.jixiejia.agent.persistence.mapper.ai.AiPublishRequestMapper;
import com.jixiejia.agent.persistence.support.CategoryService;
import com.jixiejia.agent.persistence.support.DictService;
import com.jixiejia.agent.persistence.support.RegionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 发布表单的状态机：起草 → 逐轮收集 → 校验 → 生成确认摘要与令牌。
 *
 * <p><b>为什么是状态机而不是让模型自由发挥。</b>
 * 发布是写操作。模型在这里只负责"从用户话里抽字段"，其余全部是代码决定的：
 * 有哪些字段、哪些必填、值怎么校验、什么时候可以提交。这样即使模型抽错了值，
 * 也只会被校验拦下要求重填，不会把半截数据写进库。
 *
 * <p><b>为什么状态要落库。</b>用户看完确认摘要再回复，中间是<b>另一个请求</b>，
 * 可能隔几分钟、也可能换了设备。状态放内存里，进程一重启就没了，
 * 用户会莫名其妙地"从头再来"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublishFormService {

    /** 确认令牌有效期。给用户看摘要、核对信息的时间，不用太长 */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final AiPublishRequestMapper requestMapper;
    private final PublishFieldExtractor extractor;
    private final PublishValueValidator validator;
    private final DictService dictService;
    private final RegionService regionService;
    private final CategoryService categoryService;
    private final ObjectMapper json = JsonMapper.builder().build();

    @Value("${publish.confirm-token-ttl-minutes:30}")
    private long tokenTtlMinutes;

    /** 一次推进的结果。 */
    public record Outcome(String reply, String requestNo, boolean awaitingConfirm) {
    }

    /**
     * 推进表单：从这句话里抽字段、合并、校验，然后决定下一步是继续追问还是给确认摘要。
     *
     * @param starting true 表示用户刚表达了新的发布意图，需要新开一张草稿
     */
    public Outcome advance(Long userId, String conversationId, PublishTarget target,
                           String message, boolean starting) {

        AiPublishRequest draft = starting
                ? createDraft(userId, conversationId, target)
                : findActiveDraft(conversationId);

        if (draft == null) {
            // 没找到草稿（可能过期或被清理），当作重新开始
            draft = createDraft(userId, conversationId, target);
        }

        PublishTarget draftTarget = PublishTarget.byTable(draft.getTargetTable());
        Map<String, Object> payload = readPayload(draft);

        // ① 抽取（把已知字段一起给模型，避免它重复问）
        Map<String, String> extracted = extractor.extract(draftTarget, message, payload);

        // ② 逐字段校验，失败的不写入，记下来告诉用户
        Set<String> problems = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : extracted.entrySet()) {
            PublishField field = draftTarget.field(entry.getKey());
            PublishValueValidator.Outcome outcome = validator.validate(field, entry.getValue());
            if (outcome.ok()) {
                payload.put(field.name(), outcome.value());
            } else {
                problems.add(outcome.error());
            }
        }

        // ③ 看还缺什么必填
        List<PublishField> missing = draftTarget.requiredFields().stream()
                .filter(f -> payload.get(f.name()) == null)
                .toList();

        if (!problems.isEmpty() || !missing.isEmpty()) {
            savePayload(draft, payload);
            return new Outcome(buildQuestion(draftTarget, problems, missing), draft.getRequestNo(), false);
        }

        // ④ 字段齐了：生成摘要 + 一次性令牌，等用户确认
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        draft.setConfirmToken(token);
        draft.setTokenExpireTime(LocalDateTime.now().plusMinutes(tokenTtlMinutes > 0 ? tokenTtlMinutes : TOKEN_TTL.toMinutes()));
        savePayload(draft, payload);

        return new Outcome(buildSummary(draftTarget, payload, token), draft.getRequestNo(), true);
    }

    /** 取当前会话里还没提交的草稿。 */
    public AiPublishRequest findActiveDraft(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        return requestMapper.selectOne(Wrappers.<AiPublishRequest>lambdaQuery()
                .eq(AiPublishRequest::getConversationId, conversationId)
                .eq(AiPublishRequest::getStatus, "DRAFT")
                .orderByDesc(AiPublishRequest::getId)
                .last("limit 1"));
    }

    /** 草稿是否已经生成确认令牌、正在等用户确认。 */
    public boolean isAwaitingConfirm(AiPublishRequest draft) {
        return draft != null
                && "DRAFT".equals(draft.getStatus())
                && draft.getConfirmToken() != null;
    }

    /** 令牌是否还有效。 */
    public boolean isTokenValid(AiPublishRequest draft) {
        return draft.getTokenExpireTime() != null
                && draft.getTokenExpireTime().isAfter(LocalDateTime.now());
    }

    /** 用户主动取消，作废这条草稿。 */
    public void cancel(AiPublishRequest draft) {
        draft.setStatus("REJECTED");
        draft.setConfirmToken(null);
        requestMapper.updateById(draft);
    }

    /** 令牌超时未确认，标记为已过期。 */
    public void expire(AiPublishRequest draft) {
        draft.setStatus("EXPIRED");
        draft.setConfirmToken(null);
        requestMapper.updateById(draft);
        log.info("发布请求已过期：requestNo={}", draft.getRequestNo());
    }

    public Map<String, Object> readPayload(AiPublishRequest draft) {
        String payload = draft.getPayload();
        if (payload == null || payload.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode node = json.readTree(payload);
            Map<String, Object> result = new LinkedHashMap<>();
            node.properties().forEach(e -> result.put(e.getKey(), toJavaValue(e.getValue())));
            return result;
        } catch (Exception e) {
            log.warn("发布草稿 payload 解析失败，按空处理：{}", payload);
            return new LinkedHashMap<>();
        }
    }

    /** Jackson 的节点转回普通 Java 值，写库时再用得到。 */
    private static Object toJavaValue(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(e -> map.put(e.getKey(), toJavaValue(e.getValue())));
            return map;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText(null);
    }

    private AiPublishRequest createDraft(Long userId, String conversationId, PublishTarget target) {
        // 同一会话里若还有没提交的旧草稿，先作废，避免用户换了个目标后
        // 系统还在拿旧草稿的字段追问
        AiPublishRequest previous = findActiveDraft(conversationId);
        if (previous != null) {
            previous.setStatus("REJECTED");
            previous.setReviewNote("用户重新发起了另一条发布");
            requestMapper.updateById(previous);
        }

        AiPublishRequest draft = new AiPublishRequest();
        draft.setRequestNo("PUB" + System.currentTimeMillis()
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        draft.setConversationId(conversationId);
        draft.setUserId(userId);
        draft.setTargetTable(target.table());
        draft.setPayload("{}");
        draft.setStatus("DRAFT");
        draft.setDelFlag("0");
        requestMapper.insert(draft);
        return draft;
    }

    private void savePayload(AiPublishRequest draft, Map<String, Object> payload) {
        draft.setPayload(json.writeValueAsString(payload));
        requestMapper.updateById(draft);
    }

    /** 还缺信息时的话术：先说哪里填错了，再问缺什么。 */
    private String buildQuestion(PublishTarget target, Set<String> problems, List<PublishField> missing) {
        StringBuilder sb = new StringBuilder();
        if (!problems.isEmpty()) {
            sb.append("有几处我拿不准：\n");
            problems.forEach(p -> sb.append("  · ").append(p).append('\n'));
            sb.append('\n');
        }
        if (!missing.isEmpty()) {
            sb.append("还需要你补充：\n");
            for (PublishField f : missing) {
                sb.append("  · ").append(f.label());
                if (f.hint() != null && !f.hint().isBlank()) {
                    sb.append("（").append(f.hint()).append("）");
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** 字段齐了：把要发布的内容回读给用户，并给出确认方式。 */
    private String buildSummary(PublishTarget target, Map<String, Object> payload, String token) {
        StringBuilder sb = new StringBuilder();
        sb.append("好的，请核对一下这条「").append(target.label()).append("」信息：\n\n");
        for (PublishField f : target.fields()) {
            Object value = payload.get(f.name());
            if (value == null) {
                continue;
            }
            sb.append("  ").append(f.label()).append("：").append(display(f, value)).append('\n');
        }
        sb.append("\n确认无误请回复「确认发布 ").append(token).append("」，我就提交给平台审核。\n")
                .append("需要改哪里直接告诉我。提交后信息进入待审核状态，不会立即上线。");
        return sb.toString();
    }

    /**
     * 把归一化后的值转回中文展示。
     *
     * <p>存进 payload 的是码值（字典码、分类 id、区划 id），但确认摘要里必须显示中文——
     * 让用户核对"dz""gs""110000000000" 这种值，等于没核对。
     */
    private String display(PublishField field, Object value) {
        return switch (field.kind()) {
            case DICT -> dictService.labelOrRaw(field.source(), String.valueOf(value));
            case CATEGORY -> {
                Long id = asLong(value);
                String name = id == null ? null : categoryService.name(id);
                yield name != null ? name : String.valueOf(value);
            }
            case REGION -> {
                String full = regionService.fullName(
                        PublishValueValidator.regionPart(value, "provinceId"),
                        PublishValueValidator.regionPart(value, "cityId"),
                        PublishValueValidator.regionPart(value, "districtId"));
                yield full != null ? full : String.valueOf(value);
            }
            default -> String.valueOf(value);
        };
    }

    private static Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
