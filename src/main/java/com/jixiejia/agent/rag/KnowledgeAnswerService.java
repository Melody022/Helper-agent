package com.jixiejia.agent.rag;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import com.jixiejia.agent.llm.PromptLibrary;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeTable;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
 *
 * <p>三段提示词都在 {@code classpath:prompts/} 下，改措辞不用动 Java：
 * {@code knowledge-generation}（生成回答）、{@code knowledge-self-eval}（自评）。
 * 自评那段提示词踩过坑——原来写成"出现资料中没有的数字就判否"，
 * 反而暗示模型"看到数字就否"，把正确回答误杀了；现在改成"逐条核对 + 给正例"。
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

    private final KnowledgeRetriever retriever;
    private final EvidenceGate evidenceGate;
    private final FlywheelService flywheelService;
    private final LlmClients clients;
    private final ModelCaller modelCaller;
    private final PromptLibrary prompts;
    private final AiKnowledgeTableMapper tableMapper;

    @Value("${rag.retrieval.top-k:5}")
    private int topK;

    /**
     * 送进精排的候选条数。
     *
     * <p>要明显大于 {@code top-k}：检索的排序（RRF 融合）和精排的排序不是一回事，
     * 真正该用的资料未必在检索的前 5 里。放宽到 20 让精排有机会看到它，
     * 再由精排决定最终喂哪几条——"召回放宽、精排收紧"。
     */
    @Value("${rag.retrieval.rerank-candidates:20}")
    private int rerankCandidates;

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
        // ① 检索。**取一批候选（20 条），不是直接取要喂给模型的 5 条**——
        // 检索（RRF 融合）和精排是两种排序，谁也没有义务替对方把对的资料排进前 5。
        // 实测踩过：一张表格摘要在向量那路排第 2、却因为只走单路在 RRF 里被挤出前 5，
        // 精排根本没见过它，模型只能答"表1的内容没有提供"。
        // 正确姿势是"召回放宽、精排收紧"：宽召回把资料捞进来，精排负责挑出真正相关的。
        List<KnowledgeRetriever.Hit> candidates = retriever.retrieve(question, rerankCandidates);

        // ② 证据闸：资料不够就不生成。它同时把候选按精排分排好序返回。
        EvidenceGate.Verdict gate = evidenceGate.evaluate(question, candidates, gateEnabled);
        if (!gate.passed()) {
            log.info("证据闸拦下：{}（{}）", question, gate.reason());
            flywheelService.record(FlywheelService.SOURCE_WEAK_EVIDENCE,
                    conversationId, question, null, gate.topScore());
            return new Answer(false, NOT_ENOUGH_REPLY, gate.topScore(),
                    gate.evidenceCount(), gate.reason());
        }

        // ③ 只把精排选出来的前几条喂给模型，避免无关资料干扰
        List<KnowledgeRetriever.Hit> evidence = gate.evidence().stream().limit(topK).toList();

        // ④ 生成。喂给模型之前先把命中的表格摘要换成完整表格——
        // 摘要只够"让检索命中"，拿它回答等于只给模型看表头和前几行，数据一定是错的。
        List<KnowledgeRetriever.Hit> expanded = expandTables(evidence);
        String answer = generate(question, expanded);
        if (answer == null || answer.isBlank()) {
            flywheelService.record(FlywheelService.SOURCE_WEAK_EVIDENCE,
                    conversationId, question, null, gate.topScore());
            return new Answer(false, NOT_ENOUGH_REPLY, gate.topScore(),
                    gate.evidenceCount(), "生成回答失败");
        }

        // ⑤ 自评：答案有没有超出资料。要拿**同一份**（已回填完整表格的）资料去核对，
        // 否则表格题会变成"答案里有表里的数字、但自评看到的资料里没有"，稳定误杀。
        if (selfEvalEnabled) {
            String selfVerdict = selfEvaluate(question, expanded, answer);
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

    /**
     * 把命中结果里"表格摘要"换成完整表格。
     *
     * <p>这是"方案 A"闭环的最后一环：入库时表格本体另存、只向量化摘要；
     * 检索命中的是摘要；<b>喂给模型前必须换回完整表</b>，否则模型看到的是残缺行列。
     *
     * <p>放在这里而不是检索器里，是因为证据闸要用<b>短摘要</b>判相关性——
     * 拿整张几百行的表去精排既慢又偏。所以顺序是：
     * 检索（摘要）→ 证据闸（判摘要）→ 回填完整表 → 生成。
     *
     * <p>查不到表格时保留摘要原文、不抛异常：宁可让模型凑合答，
     * 也别因为一条表格记录缺失就把整问答挂掉。
     */
    private List<KnowledgeRetriever.Hit> expandTables(List<KnowledgeRetriever.Hit> hits) {
        if (hits.stream().noneMatch(h -> h.tableId() != null)) {
            return hits;
        }

        List<Long> ids = hits.stream()
                .map(KnowledgeRetriever.Hit::tableId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, String> markdownById = tableMapper.selectBatchIds(ids).stream()
                .filter(t -> t.getMarkdown() != null)
                .collect(Collectors.toMap(AiKnowledgeTable::getId, AiKnowledgeTable::getMarkdown));

        List<KnowledgeRetriever.Hit> expanded = new ArrayList<>(hits.size());
        for (KnowledgeRetriever.Hit h : hits) {
            String full = h.tableId() == null ? null : markdownById.get(h.tableId());
            if (full == null) {
                expanded.add(h);
                continue;
            }
            expanded.add(new KnowledgeRetriever.Hit(h.vectorId(), h.docId(), h.chunkId(),
                    h.title(), full, h.rrfScore(), h.vectorScore(), h.bm25Score(),
                    h.bm25Rank(), h.knnRank(), h.tableId()));
        }
        return expanded;
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
        return modelCaller.call(clients.main(), prompts.get("knowledge-generation"),
                user, generationTimeoutMs);
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

        String verdict = modelCaller.call(clients.small(), prompts.get("knowledge-self-eval"),
                user, selfEvalTimeoutMs);
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
