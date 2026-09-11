package com.jixiejia.agent.rag;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识问答：检索 → 证据闸 → 生成 → 自评。每一步都可能把这次问答拦下来。
 *
 * <p>整条链路的顺序是刻意的：
 * <pre>
 *   检索 ──→ 证据闸 ──[资料不足]──→ 不生成，直接兜底 + 记飞轮
 *              │
 *           [资料充足]
 *              ↓
 *            生成回答
 *              ↓
 *            自评 ──[答案超出资料]──→ 换成兜底 + 记飞轮
 * </pre>
 *
 * <p>两道关都设在"话还没说出口"的位置：证据闸在生成前，自评在返回前。
 * 事后再撤回已经没意义了——用户已经看到了。
 *
 * <p>自评用小模型（本地 Ollama）而不是主模型，一是省，二是这是"判别型"任务，
 * 小模型够用。判错的代价只是多拒答一次，比让它蒙混过去安全。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeAnswerService {

    /** 资料不足时的兜底话术：明确说不确定，并给出人工通道 */
    private static final String NOT_ENOUGH_REPLY =
            "这个问题我在平台的资料里没有找到明确的依据，不想凭印象给你一个可能不准的说法。"
                    + "建议你回复\"转人工\"，让客服给你准确答复。";

    private static final String SELF_EVAL_FAIL_REPLY =
            "我找到的资料不足以完整回答这个问题，为了避免给你错误信息，建议你回复\"转人工\"让客服确认。";

    private static final String GENERATION_SYSTEM = """
            你是「机械家」二手工程机械平台的客服知识助手。

            下面会给你若干条平台资料，请**只根据这些资料**回答用户的问题。

            必须遵守：
            1. 只使用资料里写明的内容。资料没提到的，不要用常识或对其它平台的印象补充。
            2. 如果资料只能部分回答，就只答能答的部分，并说明哪部分资料里没有。
            3. 资料之间说法不一致时如实指出，不要替平台下结论。
            4. 回答口语化、简洁，直接回答用户问的，不要复述"资料说""根据资料"这类话。
            5. 不要输出 JSON 或字段名。
            """;

    /**
     * 自评提示词。
     *
     * <p>措辞踩过一次坑：原来写的是"如果回答里出现了资料中没有的具体数字、比例、时限，
     * 判为否"——本意是让它核对数字，实际效果却是暗示它"看到数字就判否"。
     * 实测中一个完全正确、数字全部来自资料的回答被误判成不合格，
     * 用户明明能得到答案，却被告知"资料不足，请转人工"。
     *
     * <p>改成"逐条核对 + 给正例"，让它先去找依据，而不是先怀疑。
     */
    private static final String SELF_EVAL_SYSTEM = """
            你在核对一段客服回答是否忠于给定资料。

            下面会给你【资料】和基于资料写出的【回答】。
            请逐条核对回答里的具体信息——尤其是数字、比例、金额、时限、条款——
            是否都能在资料里找到出处。

            判断标准：
            - 每一条信息都能在资料里找到依据 → 输出：是
            - 有任何一条在资料里找不到，或者与资料矛盾 → 输出：否

            资料里有数字而回答也用了同样的数字，算「有依据」，判「是」。
            只输出一个字：是 或 否。不要解释。
            """;

    private final KnowledgeRetriever retriever;
    private final EvidenceGate evidenceGate;
    private final FlywheelService flywheelService;
    private final LlmClients clients;
    private final ModelCaller modelCaller;

    @Value("${rag.retrieval.top-k:5}")
    private int topK;

    @Value("${rag.evidence-gate.enabled:true}")
    private boolean gateEnabled;

    @Value("${rag.self-eval.enabled:true}")
    private boolean selfEvalEnabled;

    /** 生成回答的超时。比分类宽松得多：要读完资料再写一整段 */
    @Value("${rag.answer.generation-timeout-ms:60000}")
    private long generationTimeoutMs;

    /** 自评超时。用本地小模型，要给冷启动留余量 */
    @Value("${rag.self-eval.timeout-ms:30000}")
    private long selfEvalTimeoutMs;

    /** 单次知识问答的结果。 */
    public record Answer(boolean answered, String text, double topScore,
                         int evidenceCount, String note) {
    }

    /**
     * 回答一个平台规则/知识类问题。
     *
     * @param conversationId 用于飞轮溯源，可为 null
     */
    public Answer answer(String question, String conversationId) {
        // ① 检索
        List<KnowledgeRetriever.Hit> hits = retriever.retrieve(question, topK);

        // ② 证据闸：资料不够就不生成
        EvidenceGate.Verdict gate = evidenceGate.evaluate(hits, gateEnabled);
        if (!gate.passed()) {
            log.info("证据闸拦下：{}（{}）", question, gate.reason());
            flywheelService.record(FlywheelService.SOURCE_WEAK_EVIDENCE,
                    conversationId, question, null, gate.topScore());
            return new Answer(false, NOT_ENOUGH_REPLY, gate.topScore(),
                    gate.evidenceCount(), gate.reason());
        }

        // ③ 生成
        String answer = generate(question, hits);
        if (answer == null || answer.isBlank()) {
            flywheelService.record(FlywheelService.SOURCE_WEAK_EVIDENCE,
                    conversationId, question, null, gate.topScore());
            return new Answer(false, NOT_ENOUGH_REPLY, gate.topScore(),
                    gate.evidenceCount(), "生成回答失败");
        }

        // ④ 自评：答案有没有超出资料
        if (selfEvalEnabled) {
            String selfVerdict = selfEvaluate(question, hits, answer);
            if (!"是".equals(selfVerdict)) {
                // 把模型的原始判定打出来。自评误杀是最难发现的一类问题——
                // 明明能答的问题被拒答，从用户侧看就是"这个机器人什么都不会"，
                // 而日志里只会留下"自评未通过"，看不出模型到底说了什么。
                log.info("自评未通过（模型判定：{}），改走兜底：{}", abbreviate(selfVerdict), question);
                flywheelService.record(FlywheelService.SOURCE_SELF_EVAL_FAIL,
                        conversationId, question, answer, gate.topScore());
                return new Answer(false, SELF_EVAL_FAIL_REPLY, gate.topScore(),
                        gate.evidenceCount(), "自评未通过：答案可能超出资料");
            }
        }

        return new Answer(true, answer, gate.topScore(), gate.evidenceCount(), gate.reason());
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    /** 把资料拼进提示词生成回答。 */
    private String generate(String question, List<KnowledgeRetriever.Hit> hits) {
        StringBuilder sources = new StringBuilder();
        int index = 1;
        for (KnowledgeRetriever.Hit hit : hits) {
            sources.append("<").append(index++).append(">")
                    .append(hit.title() == null ? "" : "【" + hit.title() + "】")
                    .append("\n").append(hit.content()).append("\n\n");
        }

        String user = "资料：\n" + sources + "\n用户的问题：" + question;
        // 生成要读完资料再写一整段，且主模型是推理型，必须给足时间
        return modelCaller.call(clients.main(), GENERATION_SYSTEM, user, generationTimeoutMs);
    }

    /**
     * 自评：答案是否完全来自资料。
     *
     * <p>用本地小模型。判不出来（模型不可用/返回看不懂）时**按通过处理**——
     * 自评是第二道保险，不该因为本地模型没启动就把正常问答全拒掉。
     */
    private String selfEvaluate(String question, List<KnowledgeRetriever.Hit> hits, String answer) {
        StringBuilder sources = new StringBuilder();
        for (KnowledgeRetriever.Hit hit : hits) {
            sources.append(hit.content()).append("\n");
        }
        String user = "【资料】\n" + sources + "\n【回答】\n" + answer + "\n\n回答是否完全基于资料？只答 是 或 否。";

        String verdict = modelCaller.call(clients.small(), SELF_EVAL_SYSTEM, user, selfEvalTimeoutMs);
        if (verdict == null || verdict.isBlank()) {
            // 自评是第二道保险，不该因为本地模型没启动就把正常问答全拒掉
            log.debug("自评模型不可用，默认放行");
            return "是";
        }
        // 只认第一个字：模型偶尔会多吐一句解释，取首个"是/否"即可
        String cleaned = verdict.replace("\n", "").replace("\r", "").trim();
        return cleaned.startsWith("否") ? "否" : "是";
    }
}
