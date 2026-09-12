package com.jixiejia.agent.rag;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import com.jixiejia.agent.llm.PromptLibrary;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeChunk;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeDoc;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeTable;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeChunkMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeDocMapper;
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
    private final AiKnowledgeChunkMapper chunkMapper;
    private final AiKnowledgeDocMapper docMapper;

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

    /**
     * 同章节回填开关。
     *
     * <p>开启后，命中块所在章节的前后邻块也会一起喂给模型——解决"条款被切成几块、
     * 模型只看到半截"的问题。关掉可做对照，看它对回答质量的实际影响。
     */
    @Value("${rag.retrieval.sibling-expand:true}")
    private boolean siblingExpandEnabled;

    /** 生成回答的超时。比分类宽松得多：要读完资料再写一整段 */
    @Value("${rag.answer.generation-timeout-ms:60000}")
    private long generationTimeoutMs;

    /** 自评超时。用本地小模型，要给冷启动留余量 */
    @Value("${rag.self-eval.timeout-ms:30000}")
    private long selfEvalTimeoutMs;

    /**
     * 一条答案依据。给前端做溯源展示用。
     *
     * @param docId       所属知识文档 id。<b>前端据此决定打开哪份原件</b>，缺了它点角标就没处可去
     * @param title       文档标题
     * @param sectionPath 章节路径，如 {@code "5 安全要求 > 5.4 润滑系统"}；没有时为 null
     * @param pageNo      页码（1 起）；没有时为 null
     * @param snippet     正文片段（截断过），供前端展示"依据原文"
     * @param previewable 这份原件有没有浏览器能直接看的形式（PDF/图片/纯文本，或 Office 已转出 PDF）。
     *                    提前告诉前端，免得点开一个角标才发现没有预览、还要等一次失败的请求
     */
    public record Source(Long docId, String title, String sectionPath, Integer pageNo,
                         String snippet, boolean previewable) {
    }

    /** 单次知识问答的结果。 */
    public record Answer(boolean answered, String text, double topScore,
                         int evidenceCount, String note, List<Source> sources) {

        /** 没有依据可展示时的便捷构造（拒答、闲聊等）。 */
        public Answer(boolean answered, String text, double topScore, int evidenceCount, String note) {
            this(answered, text, topScore, evidenceCount, note, List.of());
        }
    }

    /**
     * 把喂给模型的资料整理成溯源列表。
     *
     * <p>顺序就是喂给模型的顺序——资料在提示词里是 {@code <1><2>} 标号的，
     * 模型标的 {@code [1]} 指的就是这个顺序的第 1 条，所以<b>顺序不能动</b>。
     *
     * <p>可见性用包级（不是 private）是为了能直接单测：这个方法少带一个字段
     * 不会报任何错，只会让前端的溯源悄悄失效，必须有测试盯着。
     */
    static List<Source> toSources(List<KnowledgeRetriever.Hit> hits,
                                  Map<Long, AiKnowledgeDoc> docsById) {
        List<Source> sources = new ArrayList<>(hits.size());
        for (KnowledgeRetriever.Hit h : hits) {
            String content = h.content() == null ? "" : h.content().replace("\n", " ");
            AiKnowledgeDoc doc = h.docId() == null ? null : docsById.get(h.docId());
            sources.add(new Source(
                    h.docId(),
                    h.title(),
                    h.sectionPath(),
                    h.pageNo(),
                    content.length() <= 160 ? content : content.substring(0, 160) + "…",
                    previewable(doc)));
        }
        return sources;
    }

    /**
     * 这份原件有没有浏览器能直接看的形式。
     *
     * <p>规则本身在 {@link KnowledgeFileStore#previewFileOf}——它要和
     * {@code KnowledgeFileController} 的取件逻辑用同一条，
     * 所以只留这一层薄薄的投影，不在这里再写一遍判据。
     */
    static boolean previewable(AiKnowledgeDoc doc) {
        return doc != null
                && KnowledgeFileStore.previewFileOf(doc.getFilePath(), doc.getPreviewPath()) != null;
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

        // ④ 回填「本体」：表格摘要换回完整表格、命中块补上同章节的邻块。
        //    这两件事是同一个模式——检索命中的是"入口"，喂给模型前要把"本体"补全。
        List<KnowledgeRetriever.Hit> expanded = expandTables(expandSiblings(evidence));

        // 角标写法归一化（【1】/［1］/<1> → [1]）。放在这里而不是渲染层：
        // 自评、飞轮记录的都该是同一份正文，不能各处看到不同形态的角标。
        String answer = CitationSanitizer.normalize(generate(question, expanded));
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

        return new Answer(true, answer, gate.topScore(), gate.evidenceCount(), gate.reason(),
                toSources(expanded, loadDocsById(expanded)));
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    /**
     * 同章节回填：把命中块**所在章节**的相邻块也带回来。
     *
     * <p><b>解决什么问题。</b>切片是按 400 字切的，一个条款的完整意思常常跨好几块。
     * 只把命中的那一块喂给模型，它会看到"半截规定"——比如只看到"5.4.4.1 润滑系统应安全可靠"，
     * 却看不到紧接着的"5.4.4.2 减速机应…"和"5.4.4.3 报警装置应…"，答出来的东西是残缺的。
     *
     * <p><b>为什么按章节回填，而不是简单地取前后各一块</b>：纯按物理位置取，
     * 上一块可能是**另一个章节**的内容，带进来是噪声。按 {@code section_path} 限定范围，
     * 补回来的才一定和命中内容同属一节。
     *
     * <p>这就是"父子块（small-to-big）"的轻量形态——不做严格的父块存储，
     * 而是用章节路径这个天然的归属关系把兄弟块聚起来。
     */
    private List<KnowledgeRetriever.Hit> expandSiblings(List<KnowledgeRetriever.Hit> hits) {
        if (!siblingExpandEnabled) {
            return hits;
        }

        // 同一个 (文档, 章节) 可能命中多条，查一次就够
        Map<String, List<AiKnowledgeChunk>> cache =
                new java.util.HashMap<>();
        List<KnowledgeRetriever.Hit> out = new ArrayList<>(hits.size());

        for (KnowledgeRetriever.Hit h : hits) {
            String section = h.sectionPath();
            if (section == null || section.isBlank() || h.docId() == null || h.chunkId() == null) {
                out.add(h);   // 没有结构信息（FAQ、表格摘要）就原样返回
                continue;
            }

            String key = h.docId() + "|" + section;
            List<AiKnowledgeChunk> siblings =
                    cache.computeIfAbsent(key, k -> chunkMapper.selectList(
                            Wrappers.<AiKnowledgeChunk>lambdaQuery()
                                    .eq(AiKnowledgeChunk::getDocId, h.docId())
                                    .eq(AiKnowledgeChunk::getSectionPath, section)
                                    .orderByAsc(AiKnowledgeChunk::getChunkIndex)));

            String merged = mergeWithNeighbours(siblings, h.chunkId());
            out.add(merged.equals(h.content()) ? h
                    : new KnowledgeRetriever.Hit(h.vectorId(), h.docId(), h.chunkId(), h.title(),
                            merged, h.rrfScore(), h.vectorScore(), h.bm25Score(),
                            h.bm25Rank(), h.knnRank(), h.tableId(), h.sectionPath(), h.pageNo()));
        }
        return out;
    }

    /** 取命中块与它的前后邻块拼起来；找不到命中块时原样返回。 */
    private static String mergeWithNeighbours(
            List<AiKnowledgeChunk> siblings, Long chunkId) {
        int at = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (chunkId.equals(siblings.get(i).getId())) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return "";
        }

        int from = Math.max(0, at - 1);
        int to = Math.min(siblings.size() - 1, at + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            String text = siblings.get(i).getContent();
            if (text != null && !text.isBlank()) {
                sb.append(text).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** 一次把命中的文档捞出来，供溯源项判断"有没有原件可看"。 */
    private Map<Long, AiKnowledgeDoc> loadDocsById(List<KnowledgeRetriever.Hit> hits) {
        List<Long> ids = hits.stream()
                .map(KnowledgeRetriever.Hit::docId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return docMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AiKnowledgeDoc::getId, d -> d));
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
            // 注意要带上 sectionPath/pageNo：这里换个构造参数就会把它们丢成 null，
            // 表现是"表格题的回答在溯源列表里没有出处"——很难往这个方向想。
            expanded.add(new KnowledgeRetriever.Hit(h.vectorId(), h.docId(), h.chunkId(),
                    h.title(), full, h.rrfScore(), h.vectorScore(), h.bm25Score(),
                    h.bm25Rank(), h.knnRank(), h.tableId(), h.sectionPath(), h.pageNo()));
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
