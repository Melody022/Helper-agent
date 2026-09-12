package com.jixiejia.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 证据闸：在生成回答<b>之前</b>判断"检索到的资料到底够不够回答问题"。
 *
 * <p>这是防幻觉最有效的一道闸。大模型在没有资料时不会说"我不知道"，
 * 而是用它对同类平台的印象编一套出来——押金比例、手续费率、退款时限，
 * 编得像模像样，用户也没法分辨。所以只要资料不足，就不让它开口。
 *
 * <p><b>闸必须在生成之前。</b>如果先生成再判断，错话已经吐给用户了，
 * 事后再撤回已经没有意义。这也是它不能做成一个普通工具（由模型决定要不要调）的原因——
 * 模型完全可以不调它直接回答。
 *
 * <h2>两个判据，主次分明</h2>
 *
 * <p><b>主判据：精排分（{@link RerankScorer}）。</b>把问题和候选资料拼在一起过交叉编码器，
 * 直接回答"这条资料对这个问题有没有用"。它才是真正能把"能答"和"不能答"分开的信号。
 *
 * <p><b>兜底判据：向量最高相似度。</b>只在精排不可用（没配 key / 超时 / 限流）时使用。
 *
 * <h2>为什么不是"挑个更好的阈值"就完了</h2>
 *
 * <p>最初这里只有一个向量相似度阈值（{@code 0.6}），实测<b>形同虚设</b>：
 * 一个完全不相关的问题（"平台的火箭发射服务怎么预约"）相似度能拿到 0.77，
 * 远超门槛直接放行；而阈值调到真能拦住的水平，又能答的问题也被拒掉一大半。
 *
 * <p>根因是向量相似度的<b>绝对值不可靠</b>：问题向量和资料向量各自独立算完再比，
 * 两者之间没有交互，分数被 DashScope 这个模型压在一个很窄的区间里。
 * 实测标注集上，有答案的问题分数区间是 0.697~0.928，没答案的是 0.631~0.829——
 * <b>两类几乎完全重叠</b>，画完阈值曲线就能看到没有一个点能把它们分开。
 *
 * <p>所以修法不是"再拍一个数字"，而是换判据。精排分在同一批样本上是
 * 有答案中位 0.837、没答案中位 0.130（最高 0.190），中间空隙很宽，阈值才选得出来。
 * 两个阈值都写在 {@code application.yml}，校准脚本见
 * {@code src/test/java/com/jixiejia/agent/eval/RetrievalEvalTest.java}
 * （跑 {@code mvn test -Dtest=RetrievalEvalTest -Deval.enabled=true}）。
 */
@Slf4j
@Component
public class EvidenceGate {

    private final RerankFunction rerankScorer;

    /**
     * 兜底用的向量相似度门槛。只在精排不可用时生效——它分不开正负样本，
     * 但至少能滤掉"整库最高相似度也很低"这种明显没资料的情况。
     */
    private final double minRetrievalScore;

    /**
     * 精排分门槛，主判据。由评估集校准（{@code eval/RetrievalEvalTest}）：
     * 24 条有答案的样本中位 0.837，10 条无答案的最高 0.190。
     * 取 0.19 时无答案的全部被拦下（漏放 0），代价是 13% 能答的问题被误拦（转人工）——
     * 误拦会转人工，漏放会给出编造的答案，这个取舍是刻意偏保守的。
     */
    private final double minRerankScore;

    private final boolean rerankEnabled;

    public EvidenceGate(RerankFunction rerankScorer,
                        @Value("${rag.evidence-gate.min-retrieval-score:0.6}") double minRetrievalScore,
                        @Value("${rag.evidence-gate.min-rerank-score:0.19}") double minRerankScore,
                        @Value("${rag.rerank.enabled:true}") boolean rerankEnabled) {
        this.rerankScorer = rerankScorer;
        this.minRetrievalScore = minRetrievalScore;
        this.minRerankScore = minRerankScore;
        this.rerankEnabled = rerankEnabled;
    }

    /**
     * 闸门判定结果。
     *
     * @param passed        是否放行生成
     * @param topScore      向量最高相似度（兜底判据用的分）
     * @param rerankScore   精排最高分；精排不可用或未启用时为 -1
     * @param evidenceCount 达到相关性门槛的条数
     * @param reason        判定说明，落审计与飞轮
     */
    public record Verdict(boolean passed, double topScore, double rerankScore,
                          int evidenceCount, String reason) {
    }

    /**
     * 判定资料是否足够。
     *
     * @param query   用户问题。精排需要它和资料成对打分，所以必须传进来
     * @param hits    检索结果（按融合得分降序）
     * @param enabled 闸门开关，关掉时一律放行（便于做对照评估）
     */
    public Verdict evaluate(String query, List<KnowledgeRetriever.Hit> hits, boolean enabled) {
        if (!enabled) {
            return new Verdict(true, topVectorScore(hits), -1, hits == null ? 0 : hits.size(),
                    "证据闸已关闭");
        }
        if (hits == null || hits.isEmpty()) {
            return new Verdict(false, 0.0, -1, 0, "没有检索到任何资料");
        }

        double topVector = topVectorScore(hits);

        // ① 精排（主判据）。拿不到分就往下走兜底判据，不让整条链路不可用。
        Double topRerank = null;
        if (rerankEnabled && rerankScorer != null && rerankScorer.configured()) {
            List<Double> scores = rerankScorer.scoreAll(query,
                    hits.stream().map(EvidenceGate::rerankText).toList());
            if (scores != null) {
                topRerank = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            }
        }

        if (topRerank != null) {
            if (topRerank < minRerankScore) {
                return new Verdict(false, topVector, topRerank, countAbove(hits, minRetrievalScore),
                        String.format("精排判定没有可用资料：最高 %.3f 低于门槛 %.3f",
                                topRerank, minRerankScore));
            }
            return new Verdict(true, topVector, topRerank, countAbove(hits, minRetrievalScore),
                    String.format("资料充足：精排最高 %.3f（向量最高 %.3f）", topRerank, topVector));
        }

        // ② 兜底：精排不可用，退回向量相似度。它分不开正负样本，只能滤掉明显没资料的。
        if (topVector < minRetrievalScore) {
            return new Verdict(false, topVector, -1, countAbove(hits, minRetrievalScore),
                    String.format("精排不可用，退回向量判据：最高 %.3f 低于门槛 %.2f",
                            topVector, minRetrievalScore));
        }
        int effective = countAbove(hits, minRetrievalScore);
        return new Verdict(true, topVector, -1, effective,
                String.format("精排不可用，按向量判据放行：最高 %.3f，达标条数 %d", topVector, effective));
    }

    /**
     * 喂给精排的文本：<b>标题 + 正文</b>，不是只有正文。
     *
     * <p>这个细节值多少：同一批标注样本上，只喂正文时有答案的问题精排中位数 **0.379**，
     * 加上标题后变成 **0.837**；零漏放分界下的误拦率从 **29% 降到 13%**。
     *
     * <p>原因是知识库的标题本身就是**问句形态**（"押金怎么退""哪些设备不能发布"），
     * 和用户提问方式对得上；而正文是陈述式的解答，往往一次都不重复问题里的说法。
     * 只看正文，精排就没法确认"这条到底在不在回答这个问题"。
     *
     * <p>顺带也是**一致性**：生成回答时喂给模型的就是"【标题】+ 正文"
     * （见 {@link KnowledgeAnswerService#generate}），证据闸判的应当和模型看到的是同一份材料——
     * 否则会出现"闸是照着正文判的，模型却照着标题答的"这种错位。
     *
     * <p>方法暴露成 public 是给校准脚本用的（{@code eval/RetrievalEvalTest}）——
     * 评估必须测实现真正用的那个拼接方式，否则测出来的阈值对不上线上行为。
     */
    public static String rerankText(KnowledgeRetriever.Hit hit) {
        if (hit.title() == null || hit.title().isBlank()) {
            return hit.content();
        }
        return hit.title() + "\n" + hit.content();
    }

    /** 达到门槛的条数。 */
    private static int countAbove(List<KnowledgeRetriever.Hit> hits, double threshold) {
        return (int) hits.stream().filter(h -> h.vectorScore() >= threshold).count();
    }

    /** 所有结果里的最高向量相关度。 */
    private static double topVectorScore(List<KnowledgeRetriever.Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return 0.0;
        }
        return hits.stream().mapToDouble(KnowledgeRetriever.Hit::vectorScore).max().orElse(0.0);
    }

    public double minRetrievalScore() {
        return minRetrievalScore;
    }

    public double minRerankScore() {
        return minRerankScore;
    }
}
