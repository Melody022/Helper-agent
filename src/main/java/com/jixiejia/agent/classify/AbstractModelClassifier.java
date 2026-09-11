package com.jixiejia.agent.classify;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * 模型层意图分类的公共实现。第 2 层（Ollama）与第 3 层（MIMO）除了用哪个模型之外，
 * 提问方式、解析方式、失败处理完全一致，所以收在这里。
 *
 * <p>三条贯穿的原则：
 * <ol>
 *   <li><b>模型输出只当建议，不当事实。</b>返回值必须能解析成 {@link Intent} 枚举才算数，
 *       解析不出来就返回 empty 交给下一层——绝不允许"模型说了个没听过的词"被当成有效意图。</li>
 *   <li><b>模型不得触发系统命令。</b>{@link Intent#parse} 会把 {@code /reset} 这类输出判成 UNKNOWN。</li>
 *   <li><b>超时即失败，失败即降级。</b>本地模型或远端接口卡住时，宁可掉到下一层，
 *       也不能把整个对话线程挂死。</li>
 * </ol>
 */
@Slf4j
public abstract class AbstractModelClassifier {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final String SYSTEM_PROMPT = buildSystemPrompt();

    protected final LlmClients clients;
    protected final ModelCaller modelCaller;

    protected AbstractModelClassifier(LlmClients clients, ModelCaller modelCaller) {
        this.clients = clients;
        this.modelCaller = modelCaller;
    }

    /** 本层使用的模型客户端。 */
    protected abstract ChatClient client();

    /** 本层对应的分类层标识。 */
    public abstract ClassifyLayer layer();

    /** 本层在日志与审计里的名字。 */
    public abstract String layerName();

    /**
     * 调用模型做一次意图分类。
     *
     * @param userText    用户当前这句（已做过指代消解）
     * @param recentTurns 最近几轮对话，帮助判断"这个""它"之类
     * @param keywordHint 关键词层给出的倾向，作为提示而非约束
     * @return 解析成功时返回结果，否则 empty
     */
    public Optional<IntentResult> classify(String userText, String recentTurns, String keywordHint) {
        if (userText == null || userText.isBlank()) {
            return Optional.empty();
        }

        StringBuilder user = new StringBuilder();
        if (recentTurns != null && !recentTurns.isBlank()) {
            user.append("最近对话：\n").append(recentTurns).append("\n\n");
        }
        if (keywordHint != null && !keywordHint.isBlank()) {
            user.append("规则层给出的倾向（仅供参考，你可以推翻）：").append(keywordHint).append("\n\n");
        }
        user.append("用户当前这句话：").append(userText);

        try {
            String raw = modelCaller.call(client(), SYSTEM_PROMPT, user.toString());
            return parse(raw);
        } catch (Exception e) {
            // 兜底：解析或调用出任何意外都只意味着"这一层没结论"，不该影响整体路由
            log.warn("{} 层意图分类失败，降级到下一层：{}", layerName(), e.toString());
            return Optional.empty();
        }
    }

    /** 解析模型返回的 JSON。容忍 ```json 代码块包裹和前后多余文字。 */
    private Optional<IntentResult> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String payload = extractJsonObject(raw);
        if (payload == null) {
            log.debug("{} 层返回无法提取 JSON：{}", layerName(), abbreviate(raw));
            return Optional.empty();
        }

        try {
            JsonNode node = JSON.readTree(payload);
            Intent intent = Intent.parse(node.path("intent").asText(null));
            if (intent == Intent.UNKNOWN) {
                // 模型主动说"不知道"也是一种结论，但置信度按 0 记，交给下一层继续尝试
                return Optional.of(IntentResult.of(Intent.UNKNOWN, 0.0, layer(), "模型返回 UNKNOWN"));
            }

            double confidence = node.path("confidence").asDouble(0.5);
            confidence = Math.max(0.0, Math.min(1.0, confidence));

            return Optional.of(IntentResult.of(intent, confidence, layer(),
                    "模型判定：" + payload, readDomains(node)));

        } catch (Exception e) {
            log.debug("{} 层返回 JSON 解析失败：{}", layerName(), abbreviate(raw));
            return Optional.empty();
        }
    }

    /** 读取跨域时模型给出的领域列表。非跨域、缺失或格式不对都返回空集合。 */
    private static java.util.Set<String> readDomains(JsonNode node) {
        JsonNode domainsNode = node.path("domains");
        if (!domainsNode.isArray()) {
            return java.util.Set.of();
        }
        java.util.Set<String> domains = new java.util.LinkedHashSet<>();
        domainsNode.forEach(n -> {
            String v = n.asText(null);
            if (v != null && !v.isBlank()) {
                domains.add(v.trim().toLowerCase());
            }
        });
        return domains;
    }

    /** 从可能带解释文字的回复里截出第一个完整的 JSON 对象。 */
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

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「机械家」二手工程机械平台（业务含设备买卖、出租、求租、需求询价、资讯、论坛）")
                .append("的意图分类器。你的唯一任务是把用户这句话归到一个意图上。\n\n")
                .append("可选意图如下，你只能返回下面出现过的名字：\n");

        for (Intent intent : Intent.values()) {
            sb.append("- ").append(intent.name()).append("：").append(intent.description()).append("\n");
        }

        sb.append("""

                规则：
                1. 只输出一个 JSON 对象，不要输出任何别的内容：
                   普通情况形如 {"intent":"CHUZU_QUERY","confidence":0.92}
                   跨域时形如 {"intent":"CROSS_DOMAIN","confidence":0.9,"domains":["equipment","chuzu"]}
                2. intent 必须是上面列表里的名字，不得自创，也不得输出 /reset 这类命令。
                3. 一句话里同时问了两个及以上**不同领域**的问题时选 CROSS_DOMAIN，
                   并在 domains 里列出涉及的领域。domains 的取值只能是：
                   equipment(设备买卖) / chuzu(出租) / qiuzu(求租) / demand(用机需求询价) / news(资讯) / policy(平台规则)。
                   注意 chuzu、qiuzu、demand 都属于"租赁"这一块业务，
                   如果只问了其中一个，不算跨域。
                4. 判断不了就选 UNKNOWN，并把 confidence 打到 0.3 以下，不要瞎猜。
                5. confidence 是你对判断的把握，取值 0 到 1。
                """);
        return sb.toString();
    }
}
