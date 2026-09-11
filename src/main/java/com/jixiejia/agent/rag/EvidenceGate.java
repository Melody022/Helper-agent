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
 * <p>两个判据配合使用：
 * <ul>
 *   <li><b>最高相关性分</b>——最相关的那条资料到底有多相关，防止"矮子里拔将军"；</li>
 *   <li><b>有效证据条数</b>——有几条资料真的相关，防止只有一条勉强沾边就作答。</li>
 * </ul>
 * 阈值必须用评估集校准，不能拍脑袋：设高了会把能答的问题也拒掉，设低了等于没闸。
 */
@Slf4j
@Component
public class EvidenceGate {

    private final double minRetrievalScore;
    private final int minEvidenceCount;

    public EvidenceGate(@Value("${rag.evidence-gate.min-retrieval-score:0.6}") double minRetrievalScore,
                        @Value("${rag.evidence-gate.min-evidence-count:1}") int minEvidenceCount) {
        this.minRetrievalScore = minRetrievalScore;
        this.minEvidenceCount = minEvidenceCount;
    }

    /**
     * 闸门判定结果。
     *
     * @param passed        是否放行生成
     * @param topScore      最相关那条的相关性分
     * @param evidenceCount 达到相关性门槛的条数
     * @param reason        判定说明，落审计与飞轮
     */
    public record Verdict(boolean passed, double topScore, int evidenceCount, String reason) {
    }

    /**
     * 判定资料是否足够。
     *
     * <p>两个判据按顺序检查，各管一件事，不重叠：
     * <ol>
     *   <li><b>最相关的那条够不够相关</b>——先排除"压根没有相关资料"的情况。
     *       用户问的东西知识库里完全没有时，检索仍会返回一堆弱相关的切片，
     *       只看条数会误判成"有资料"；</li>
     *   <li><b>达标的条数够不够多</b>——再排除"只有一条勉强沾边"的情况。
     *       min-evidence-count 设为 1 时这一条几乎不会触发，调到 2 以上才有实际约束。</li>
     * </ol>
     *
     * @param hits    检索结果（按融合得分降序）
     * @param enabled 闸门开关，关掉时一律放行（便于做对照评估）
     */
    public Verdict evaluate(List<KnowledgeRetriever.Hit> hits, boolean enabled) {
        if (!enabled) {
            return new Verdict(true, topScore(hits), hits.size(), "证据闸已关闭");
        }
        if (hits == null || hits.isEmpty()) {
            return new Verdict(false, 0.0, 0, "没有检索到任何资料");
        }

        double top = topScore(hits);
        int effective = (int) hits.stream().filter(h -> h.vectorScore() >= minRetrievalScore).count();

        if (top < minRetrievalScore) {
            return new Verdict(false, top, effective,
                    String.format("最相关的资料相关度也不足：最高 %.3f 低于门槛 %.2f",
                            top, minRetrievalScore));
        }
        if (effective < minEvidenceCount) {
            return new Verdict(false, top, effective,
                    String.format("达标资料条数不足：%d 条达到 %.2f 的门槛，要求 %d 条",
                            effective, minRetrievalScore, minEvidenceCount));
        }

        return new Verdict(true, top, effective,
                String.format("资料充足：最高相关度 %.3f，达标条数 %d", top, effective));
    }

    /** 所有结果里的最高向量相关度。 */
    private static double topScore(List<KnowledgeRetriever.Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return 0.0;
        }
        return hits.stream().mapToDouble(KnowledgeRetriever.Hit::vectorScore).max().orElse(0.0);
    }

    public double minRetrievalScore() {
        return minRetrievalScore;
    }

    public int minEvidenceCount() {
        return minEvidenceCount;
    }
}
