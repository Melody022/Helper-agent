package com.jixiejia.agent.eval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.jixiejia.agent.rag.EvidenceGate;
import com.jixiejia.agent.rag.KnowledgeIndex;
import com.jixiejia.agent.rag.KnowledgeIngestionService;
import com.jixiejia.agent.rag.KnowledgeRetriever;
import com.jixiejia.agent.rag.RerankScorer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索质量评估 + 证据闸阈值校准。
 *
 * <p><b>为什么要有这个。</b>两个问题一直只能用"设计上说得通"来回答，没有数据：
 * <ol>
 *   <li><b>混合召回到底比单路好在哪</b>——之前只有"关键词搜『手续费』一条都匹配不到"这一个孤例；</li>
 *   <li><b>证据闸的 {@code min-retrieval-score:0.6} 是拍脑袋定的</b>——实测一个完全不相关的问题
 *       （"平台的火箭发射服务怎么预约"）向量相关度能拿到 0.77，超过门槛，闸直接放行。</li>
 * </ol>
 * 两个问题其实是一件事：都需要一份"这个问题该命中哪条知识"的人工标注集。
 * 有了它就能算出 Recall@K，也能画出"阈值 vs 误拦率/漏放率"曲线。
 *
 * <p><b>评估集怎么标的。</b>{@link #LABELED} 里每条样本是「用户问法 → 期望命中的知识标题」，
 * 标题对应语料里的一级标题（也是入库时的文档 title）。凡是在知识库里<b>确实有答案</b>的，
 * 标注期望命中；凡是没有答案的（{@link #UNANSWERABLE}），标注为"应当被证据闸拦下"。
 *
 * <p>标注原则和意图评估集一致：<b>按用户真正想问什么标，不挑简单样本</b>。
 * 所以这里刻意放了不少口语化说法（"租机器要先交多少钱"）和同义改写（"手续费"↔"服务费"），
 * 也有跨条目的问题（"从下单到退场整个流程和费用"）。
 *
 * <p>默认不参与常规测试（要调 embedding 接口、跑一遍十几秒），需要时手动跑：
 * <pre>
 *   mvn test -Dtest=RetrievalEvalTest -Deval.enabled=true
 * </pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "eval.enabled", matches = "true")
class RetrievalEvalTest {

    /** 一条标注样本：用户问法 → 期望命中的知识标题（可能不止一条）。 */
    private record Sample(String question, String... expectedTitles) {
    }

    /**
     * 知识库里<b>有答案</b>的问题。期望标题写的是语料里的一级标题，也就是入库后的 doc title。
     *
     * <p>覆盖三类问法：标准问法、口语化问法、同义改写，外加一条跨条目的。
     */
    private static final List<Sample> LABELED = List.of(
            new Sample("平台收多少服务费", "平台收取多少服务费"),
            new Sample("你们抽几个点", "平台收取多少服务费"),
            new Sample("手续费怎么算", "平台收取多少服务费"),   // 语料里写的是"服务费"，纯关键词搜不到

            new Sample("押金怎么退", "租设备要交押金吗，押金怎么退"),
            new Sample("租机器要先交多少钱", "租设备要交押金吗，押金怎么退"),
            new Sample("押金什么时候能拿回来", "租设备要交押金吗，押金怎么退"),

            new Sample("在平台租设备的流程是什么", "在平台租设备的流程"),
            new Sample("怎么租设备", "在平台租设备的流程"),
            new Sample("租设备要走哪些步骤", "在平台租设备的流程"),

            new Sample("怎么发布出租信息", "怎么发布出租信息，审核要多久"),
            new Sample("发布之后多久能审核通过", "怎么发布出租信息，审核要多久"),

            new Sample("我的发布为什么审核不通过", "发布的信息为什么审核不通过"),

            new Sample("平台支持哪些付款方式", "平台支持哪些付款方式"),
            new Sample("可以线下打钱给机主吗", "平台支持哪些付款方式"),

            new Sample("设备验收有问题怎么办", "设备验收有问题怎么办"),
            new Sample("收到的机器和描述不符", "设备验收有问题怎么办"),

            new Sample("租金怎么支付", "租金怎么支付，可以分期吗"),
            new Sample("租金能分期吗", "租金怎么支付，可以分期吗"),

            new Sample("平台有质保吗", "平台对买卖的设备提供质保吗"),

            new Sample("账号被冻结了怎么解封", "账号被冻结了怎么解封"),

            new Sample("哪些设备不能发布", "哪些设备不能发布"),

            new Sample("怎么核实卖家的真实性", "怎么核实卖家的真实性"),

            // 跨条目：流程与费用两个知识点都该被捞到
            new Sample("从下单到退场整个流程和费用是怎么算的", "在平台租设备的流程", "平台收取多少服务费"),

            // 资讯类（来自 cms_article，验证文章也能被检索到）
            new Sample("有什么沃尔沃电动挖掘机的资讯", "【2020 我在宝马展】沃尔沃55 电动智能挖掘机")
    );

    /**
     * 知识库里<b>没有答案</b>的问题——证据闸应当全部拦下。
     *
     * <p>这批样本是"误拦率/漏放率"曲线里的<b>负样本</b>，和 {@link #LABELED} 的正样本配对。
     * 来源分两类：一是完全无关的（火箭发射、天气预报），二是"像平台业务但不属于任何一个知识条目"的
     * （能不能开发票、支不支持信用卡）——第二类才是真正难分辨的，因为它们的向量距离天然更近。
     */
    private static final List<String> UNANSWERABLE = List.of(
            "平台的火箭发射服务怎么预约",
            "明天北京天气怎么样",
            "帮我写一首关于挖掘机的诗",
            "平台能不能开发票",
            "平台支持刷信用卡吗",
            "你们公司在哪个城市",
            "挖掘机驾照怎么考",
            "二手的设备能不能按揭贷款",
            "租设备包不包司机",
            "平台有没有优惠券可以领"
    );

    @Autowired
    private KnowledgeRetriever retriever;

    @Autowired
    private KnowledgeIngestionService ingestionService;

    @Autowired
    private KnowledgeIndex knowledgeIndex;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private ElasticsearchClient es;

    @Autowired
    private RerankScorer rerankScorer;

    @Autowired
    private EvidenceGate gate;

    /** 检索取多少条，与 {@code rag.retrieval.top-k} 保持一致。 */
    private static final int TOP_K = 5;

    /**
     * 精排前先召回多少条候选，与 {@code rag.retrieval.rerank-candidates} 保持一致。
     *
     * <p>线上是「召回这么些条 → 精排排序 → 取前 {@link #TOP_K} 条喂模型」，
     * 所以只看融合层的 top-5 会低估系统的实际能力：真正被淘汰的候选，
     * 有一部分本来能被精排捞回来。
     */
    @Value("${rag.retrieval.rerank-candidates:20}")
    private int rerankCandidates;

    /** 正式评估：Recall@K 三路对比 + 证据闸阈值曲线。 */
    @Test
    @DisplayName("检索评估：三路召回 Recall@K 对比 + 证据闸阈值校准")
    void evaluateRetrievalAndCalibrateGate() throws Exception {
        Assumptions.assumeTrue(knowledgeIndex.available(), "Elasticsearch 未启动，跳过");

        // 语料保证：内置 FAQ 与平台文章都灌一遍（没变的会跳过）
        ingestionService.ingestBuiltinFaq();
        ingestionService.ingestArticles();
        Thread.sleep(1500);   // ES 近实时，写入后要等一个刷新周期

        // ① 三路召回各自的 Recall@5 + 「召回 20 → 精排 → 取 5」的整条流水线
        Map<String, double[]> modes = new LinkedHashMap<>();   // 模式名 -> [命中条数, 总数]
        for (String mode : List.of("bm25", "vector", "hybrid", "pipeline")) {
            modes.put(mode, new double[]{0, 0});
        }
        List<String> misses = new ArrayList<>();
        List<String> pipelineMisses = new ArrayList<>();

        for (Sample sample : LABELED) {
            for (String mode : modes.keySet()) {
                List<KnowledgeRetriever.Hit> hits = switch (mode) {
                    case "bm25" -> retrieveBm25Only(sample.question(), 5);
                    case "vector" -> retrieveVectorOnly(sample.question(), 5);
                    case "pipeline" -> retrievePipeline(sample.question(), 5);
                    default -> retriever.retrieve(sample.question(), 5);
                };
                modes.get(mode)[1]++;
                if (containsAll(hits, sample.expectedTitles())) {
                    modes.get(mode)[0]++;
                } else if ("hybrid".equals(mode)) {
                    misses.add(String.format("  %-28s 期望 %s%n      实际 %s",
                            sample.question(), List.of(sample.expectedTitles()),
                            hits.stream().map(KnowledgeRetriever.Hit::title).toList()));
                } else if ("pipeline".equals(mode)) {
                    pipelineMisses.add(String.format("  %-28s 期望 %s%n      实际 %s",
                            sample.question(), List.of(sample.expectedTitles()),
                            hits.stream().map(KnowledgeRetriever.Hit::title).toList()));
                }
            }
        }

        // ② 证据闸候选判据：正负样本上各自的表现
        List<Feature> positiveFeatures = new ArrayList<>();
        for (Sample sample : LABELED) {
            positiveFeatures.add(features(sample.question()));
        }
        List<Feature> negativeFeatures = new ArrayList<>();
        for (String question : UNANSWERABLE) {
            negativeFeatures.add(features(question));
        }

        System.out.println(buildReport(modes, misses, pipelineMisses, positiveFeatures, negativeFeatures, gate));

        // 断言下限而不是精确值：embedding 接口与 ES 都有波动。
        // 但混合召回若明显退化（比如掉到 60% 以下），说明链路真出问题了。
        double hybridRecall = modes.get("hybrid")[0] / modes.get("hybrid")[1];
        assertThat(hybridRecall)
                .as("混合召回 Recall@5 不应低于 70%%")
                .isGreaterThanOrEqualTo(0.70);
    }

    // ---------------- 单路召回（用于对比） ----------------

    /** 只用关键词（BM25）。 */
    private List<KnowledgeRetriever.Hit> retrieveBm25Only(String query, int topK) throws Exception {
        var response = es.search(s -> s.index(KnowledgeIndex.NAME).size(topK)
                .query(q -> q.match(m -> m.field(KnowledgeIndex.fieldText()).query(query))),
                Map.class);
        List<KnowledgeRetriever.Hit> hits = new ArrayList<>();
        int rank = 0;
        for (var hit : response.hits().hits()) {
            Map<?, ?> source = hit.source();
            if (source == null) {
                continue;
            }
            rank++;
            hits.add(new KnowledgeRetriever.Hit(hit.id(), null, null,
                    str(source.get(KnowledgeIndex.fieldTitle())),
                    str(source.get(KnowledgeIndex.fieldText())),
                    0, 0, hit.score() == null ? 0 : hit.score(), rank, -1));
        }
        return hits;
    }

    /** 只用语义（KNN）。 */
    private List<KnowledgeRetriever.Hit> retrieveVectorOnly(String query, int topK) throws Exception {
        float[] vector = embeddingModel.embed(query);
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        var response = es.search(s -> s.index(KnowledgeIndex.NAME).size(topK)
                        .knn(k -> k.field(KnowledgeIndex.fieldVector()).queryVector(list)
                                .k(topK).numCandidates(100)),
                Map.class);
        List<KnowledgeRetriever.Hit> hits = new ArrayList<>();
        int rank = 0;
        for (var hit : response.hits().hits()) {
            Map<?, ?> source = hit.source();
            if (source == null) {
                continue;
            }
            rank++;
            hits.add(new KnowledgeRetriever.Hit(hit.id(), null, null,
                    str(source.get(KnowledgeIndex.fieldTitle())),
                    str(source.get(KnowledgeIndex.fieldText())),
                    0, hit.score() == null ? 0 : hit.score(), 0, -1, rank));
        }
        return hits;
    }

    /**
     * 完整流水线：混合召回 {@code rerankCandidates} 条 → 精排排序 → 取前 topK 条。
     *
     * <p>这才是线上真正喂给模型的 5 条，也是唯一有资格写进结论的召回数字。
     * 只用 {@code retriever.retrieve(q, 5)} 量的是融合层，会低估——
     * 融合层排在第 6~20 名、但精排能捞回来的那些资料，在那种口径下全被算成"漏了"。
     */
    private List<KnowledgeRetriever.Hit> retrievePipeline(String query, int topK) {
        try {
            List<KnowledgeRetriever.Hit> candidates = retriever.retrieve(query, rerankCandidates);
            List<Double> scores = rerankScorer.scoreAll(query,
                    candidates.stream().map(EvidenceGate::rerankText).toList());
            if (scores == null || scores.size() != candidates.size()) {
                // 精排不可用时线上会退回融合顺序，评估也照这个退法，别造出一个线上不存在的路径
                return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
            }
            List<KnowledgeRetriever.Hit> ranked = new ArrayList<>(candidates);
            List<Double> order = new ArrayList<>(scores);
            // 按下标一起排，避免"分数排序后和资料对不上"
            Integer[] idx = new Integer[ranked.size()];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = i;
            }
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(order.get(b), order.get(a)));
            List<KnowledgeRetriever.Hit> result = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, idx.length); i++) {
                result.add(ranked.get(idx[i]));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 一个问题在整库里的最高向量相关度。只用于对比，不参与最终判定——
     * 证据闸实际是在召回后的候选集上算的。
     */
    private double topVectorScore(String query) {
        try {
            List<KnowledgeRetriever.Hit> hits = retrieveVectorOnly(query, 20);
            return hits.stream().mapToDouble(KnowledgeRetriever.Hit::vectorScore).max().orElse(0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * 一条样本上可用的几个"证据够不够"判据。
     *
     * <p>为什么要比较多个判据：向量相关度的绝对值和模型、语料都强相关，
     * DashScope 这种模型的分数整体偏高且被压缩在 0.6~0.93 这个窄区间里，
     * 正负样本几乎完全重叠。所以除了"最高分"，还测量真正在用的主判据——
     * 精排分（{@link RerankScorer}，把问题和资料拼在一起过交叉编码器）。
     *
     * <p>精排只在<b>证据闸实际会看到的那批候选</b>上算（即混合召回的 top-k），
     * 而不是对全库算——否则测出来的分布和线上跑的不是一回事。
     */
    private record Feature(double vectorTop, double rerankTop) {
    }

    private Feature features(String query) {
        try {
            List<KnowledgeRetriever.Hit> hits = retriever.retrieve(query, TOP_K);
            double vectorTop = hits.stream()
                    .mapToDouble(KnowledgeRetriever.Hit::vectorScore).max().orElse(0.0);

            List<Double> rerank = rerankScorer.scoreAll(query,
                    hits.stream().map(EvidenceGate::rerankText).toList());
            double rerankTop = rerank == null ? -1
                    : rerank.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

            return new Feature(vectorTop, rerankTop);
        } catch (Exception e) {
            return new Feature(0, -1);
        }
    }

    private static boolean containsAll(List<KnowledgeRetriever.Hit> hits, String[] expectedTitles) {
        Set<String> titles = new HashSet<>();
        for (KnowledgeRetriever.Hit hit : hits) {
            if (hit.title() != null) {
                titles.add(hit.title());
            }
        }
        for (String expected : expectedTitles) {
            if (!titles.contains(expected)) {
                return false;
            }
        }
        return true;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---------------- 报告 ----------------

    private String buildReport(Map<String, double[]> modes, List<String> misses,
                               List<String> pipelineMisses,
                               List<Feature> positive, List<Feature> negative, EvidenceGate gate) {
        StringBuilder sb = new StringBuilder();
        int k = 5;

        sb.append("\n================ 检索质量评估报告 ================\n");
        sb.append(String.format("标注集：%d 条有答案 + %d 条无答案%n",
                LABELED.size(), UNANSWERABLE.size()));

        sb.append("\n---- Recall@").append(k).append("：三种口径对比 ----\n");
        sb.append("  （bm25/vector/hybrid 只到融合层；pipeline 才是线上真正喂给模型的 5 条）\n");
        for (Map.Entry<String, double[]> e : modes.entrySet()) {
            double[] stat = e.getValue();
            sb.append(String.format("  %-8s %2.0f/%2.0f  %5.1f%%%n",
                    e.getKey(), stat[0], stat[1], 100.0 * stat[0] / stat[1]));
        }

        if (!pipelineMisses.isEmpty()) {
            sb.append("\n---- 流水线真正漏掉的（线上会答偏/答不上的）----\n");
            pipelineMisses.forEach(m -> sb.append(m).append('\n'));
        }

        if (!misses.isEmpty()) {
            sb.append("\n---- 融合层漏掉、但精排可能捞回来的（只作诊断用）----\n");
            misses.forEach(m -> sb.append(m).append('\n'));
        }

        // 逐个候选判据评估：谁能把正负样本分开
        sb.append("\n---- 证据闸判据对比：哪个信号能把「有答案」和「没答案」分开 ----\n");
        sb.append(evaluateDiscriminator("向量最高相关度(兜底判据)", positive, negative));
        sb.append(evaluateDiscriminator("精排分(主判据)", positive, negative));

        sb.append("\n   说明：正负样本的分数区间重叠越多，单靠该判据越分不开。\n");
        sb.append("   误拦率低但漏放率高 = 闸太松（会编答案）；两者都低才算真的能用。\n");
        sb.append("   当前配置：min-retrieval-score=").append(gate.minRetrievalScore())
                .append("  min-rerank-score=").append(gate.minRerankScore()).append('\n');

        sb.append("==================================================\n");
        return sb.toString();
    }

    /** 对一个判据画阈值曲线，打印分布区间与几个关键阈值下的错误情况。 */
    private String evaluateDiscriminator(String name, List<Feature> positive, List<Feature> negative) {
        // 精排不可用时它的分是 -1，这类样本不参与该判据的评估
        List<Double> pos = positive.stream().map(f -> pick(f, name))
                .filter(v -> v >= 0).sorted().toList();
        List<Double> neg = negative.stream().map(f -> pick(f, name))
                .filter(v -> v >= 0).sorted().toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n  [%s]%n", name));
        if (pos.isEmpty() || neg.isEmpty()) {
            return sb.append("    数据不足，跳过\n").toString();
        }
        sb.append(String.format("    有答案  最低 %.3f  中位 %.3f  最高 %.3f%n",
                pos.get(0), pos.get(pos.size() / 2), pos.get(pos.size() - 1)));
        sb.append(String.format("    无答案  最低 %.3f  中位 %.3f  最高 %.3f%n",
                neg.get(0), neg.get(neg.size() / 2), neg.get(neg.size() - 1)));

        double lo = Math.min(pos.get(0), neg.get(0));
        double hi = Math.max(pos.get(pos.size() - 1), neg.get(neg.size() - 1));
        double bestT = lo;
        long bestErrors = Long.MAX_VALUE;
        long bestFalseReject = 0;
        long bestFalseAccept = 0;
        for (int i = 0; i <= 200; i++) {
            double t = lo + (hi - lo) * i / 200.0;
            long fr = pos.stream().filter(s -> s < t).count();
            long fa = neg.stream().filter(s -> s >= t).count();
            if (fr + fa < bestErrors) {
                bestErrors = fr + fa;
                bestT = t;
                bestFalseReject = fr;
                bestFalseAccept = fa;
            }
        }
        sb.append(String.format("    最优分界 %.3f → 误拦 %d/%d (%.0f%%)  漏放 %d/%d (%.0f%%)%n",
                bestT, bestFalseReject, pos.size(), 100.0 * bestFalseReject / pos.size(),
                bestFalseAccept, neg.size(), 100.0 * bestFalseAccept / neg.size()));

        // 零漏放分界：比所有负样本都高的最小阈值——只要没有负样本能到这个分，
        // 就能把"不相关的资料"全拦下，代价是误拦掉一部分能答的问题
        double zeroLeakT = neg.get(neg.size() - 1) + 0.001;
        long fr = pos.stream().filter(s -> s < zeroLeakT).count();
        sb.append(String.format("    零漏放分界 %.3f → 误拦 %d/%d (%.0f%%)%n",
                zeroLeakT, fr, pos.size(), 100.0 * fr / pos.size()));
        return sb.toString();
    }

    private static double pick(Feature f, String name) {
        return name.startsWith("向量") ? f.vectorTop() : f.rerankTop();
    }
}

