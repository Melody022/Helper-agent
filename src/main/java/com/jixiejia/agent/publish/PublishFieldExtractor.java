package com.jixiejia.agent.publish;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从用户的话里把表单字段抠出来。
 *
 * <p>这是模型在发布流程里的<b>唯一</b>职责：它只做"抽取"，
 * 不做"决定要发布什么"、更不碰落库。字段名来自代码里写死的表单定义
 * （见 {@link PublishTarget}），模型返回的键如果不认识就直接丢掉——
 * 它编一个字段名出来也没用，因为落库时只认表单里有的字段。
 *
 * <p>返回值里<b>只包含真的找到的字段</b>。没提到的字段不返回，
 * 而不是返回空串——否则会把用户上一轮已经填好的值覆盖成空。
 */
@Slf4j
@Component
public class PublishFieldExtractor {

    private final LlmClients clients;
    private final ModelCaller modelCaller;
    private final ObjectMapper json = JsonMapper.builder().build();

    @Value("${routing.llm.timeout-ms:45000}")
    private long timeoutMs;

    public PublishFieldExtractor(LlmClients clients, ModelCaller modelCaller) {
        this.clients = clients;
        this.modelCaller = modelCaller;
    }

    /**
     * 抽取字段。
     *
     * @param target       发布目标（决定有哪些字段）
     * @param text         用户这句话
     * @param alreadyKnown 已经填好的字段，作为上下文给模型看，避免它重复追问
     * @return 本次新抽到的字段，可能为空
     */
    public Map<String, String> extract(PublishTarget target, String text,
                                       Map<String, Object> alreadyKnown) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }

        String answer = modelCaller.call(clients.main(), buildSystemPrompt(target),
                buildUserPrompt(target, text, alreadyKnown), timeoutMs);
        if (answer == null || answer.isBlank()) {
            log.warn("发布字段抽取未返回内容");
            return Map.of();
        }

        return parse(target, answer);
    }

    private String buildSystemPrompt(PublishTarget target) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在帮用户填写一条「").append(target.label()).append("」信息的发布表单。\n\n")
                .append("表单字段如下：\n");
        for (PublishField f : target.fields()) {
            sb.append("- ").append(f.name()).append("（").append(f.label()).append("）");
            if (f.hint() != null && !f.hint().isBlank()) {
                sb.append("：").append(f.hint());
            }
            sb.append(f.required() ? "  [必填]" : "  [选填]").append('\n');
        }
        sb.append("""

                从用户这句话里把能确定的字段抽出来，只输出一个 JSON 对象，形如：
                {"machineType":"挖掘机","tonage":"6-9吨"}

                规则：
                1. **只输出真的能从这句话里确定的字段**，没提到的不要输出，也不要给空字符串。
                2. 只能用上面列出的字段名，不要自己造字段。
                3. 原样保留用户说的值，不要改写、不要换算、不要补充。比如用户说"一天一千"，
                   就填"一天一千"，不要自作主张写成"1000/天"。
                4. 如果用户的话里有多个值对应同一个字段（改了主意），取最后说的那个。
                5. 什么都抽不出来就输出 {}。
                """);
        return sb.toString();
    }

    private String buildUserPrompt(PublishTarget target, String text, Map<String, Object> alreadyKnown) {
        StringBuilder sb = new StringBuilder();
        if (alreadyKnown != null && !alreadyKnown.isEmpty()) {
            sb.append("用户之前已经提供过这些信息（不要重复追问）：\n");
            alreadyKnown.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
            sb.append('\n');
        }
        sb.append("用户这句话：").append(text);
        return sb.toString();
    }

    /** 解析模型返回的 JSON，只保留表单里认识的字段。 */
    private Map<String, String> parse(PublishTarget target, String raw) {
        String payload = extractJsonObject(raw);
        if (payload == null) {
            log.debug("发布字段抽取未找到 JSON：{}", raw);
            return Map.of();
        }

        try {
            JsonNode node = json.readTree(payload);
            if (!node.isObject()) {
                return Map.of();
            }

            Map<String, String> result = new LinkedHashMap<>();
            node.properties().forEach(entry -> {
                String name = entry.getKey();
                // 字段名必须是表单里定义过的，模型自造的键直接丢
                boolean known = target.fields().stream().anyMatch(f -> f.name().equals(name));
                if (!known) {
                    log.debug("忽略模型自造的字段：{}", name);
                    return;
                }
                String value = entry.getValue().asText("").trim();
                if (!value.isEmpty()) {
                    result.put(name, value);
                }
            });
            return result;

        } catch (Exception e) {
            log.debug("发布字段抽取结果解析失败：{}", raw);
            return Map.of();
        }
    }

    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
