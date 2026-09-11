package com.jixiejia.agent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索：关键词（BM25）与语义（KNN）两路并行召回，用 RRF 融合。
 *
 * <p><b>为什么要两路。</b>关键词召回对"押金""手续费"这类专有名词准，
 * 但用户换成"租机器要先交多少钱"就匹配不上；向量召回能听懂同义说法，
 * 但对专有名词又不够精确。两路各自排序后融合，正好互补。
 *
 * <p><b>为什么用 RRF 而不是把两路分数加权相加。</b>
 * BM25 的分数是无上界的（几分到几十分都有），向量相似度是 0~1，
 * 两者量纲不同，直接加权重会把 BM25 的结果完全盖住，权重调起来也没个准。
 * RRF 只用"名次"不用"分数"：某条结果在任一路排第 r 名就得 1/(k+r) 分，
 * 两路都排前面的自然分高。不需要归一化，也不怕两路分数量纲不同。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetriever {

    /**
     * RRF 的平滑常数。取 60 是原论文的推荐值：
     * 它决定了"名次差距"对得分的影响有多剧烈——k 越大，前几名之间的差距越小、越平缓。
     */
    private static final int RRF_K = 60;

    private final ElasticsearchClient es;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeIndex knowledgeIndex;

    /** 每路各召回多少条候选，融合后再截断。取 topK 的几倍，给融合留出腾挪空间。 */
    @Value("${rag.retrieval.candidates:20}")
    private int candidates;

    /** KNN 检索考察的候选向量数，必须 >= k */
    @Value("${rag.retrieval.num-candidates:100}")
    private int numCandidates;

    /**
     * 一条检索结果。
     *
     * @param rrfScore    融合后的得分，用于排序
     * @param vectorScore 向量相似度 0~1，用于证据闸判断"资料到底相不相关"
     * @param bm25Score   关键词得分，无上界，仅供排查
     */
    public record Hit(String vectorId, Long docId, Long chunkId, String title, String content,
                      double rrfScore, double vectorScore, double bm25Score,
                      int bm25Rank, int knnRank) {
    }

    /**
     * 检索与问题最相关的若干切片。
     *
     * @return 按融合得分降序；ES 不可用或无结果时返回空列表
     */
    public List<Hit> retrieve(String query, int topK) {
        if (!knowledgeIndex.available() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            float[] queryVector = embeddingModel.embed(query);

            List<Hit> bm25 = searchByKeyword(query);
            List<Hit> knn = searchByVector(queryVector);

            return fuse(bm25, knn, topK);

        } catch (Exception e) {
            // 检索失败只意味着"这次没查到资料"，由证据闸兜底，不该把异常抛给对话
            log.warn("知识检索失败：{}", e.toString());
            return List.of();
        }
    }

    /** 关键词召回。用 match 查询走 IK 分词，让"挖掘机出租"能匹配到含这两个词的切片。 */
    private List<Hit> searchByKeyword(String query) throws Exception {
        SearchResponse<Map> response = es.search(s -> s
                        .index(KnowledgeIndex.NAME)
                        .size(candidates)
                        .query(q -> q.match(m -> m
                                .field(KnowledgeIndex.fieldText())
                                .query(query))),
                Map.class);

        List<Hit> hits = new ArrayList<>();
        int rank = 0;
        for (co.elastic.clients.elasticsearch.core.search.Hit<Map> hit : response.hits().hits()) {
            Map<?, ?> source = hit.source();
            if (source == null) {
                continue;
            }
            rank++;
            hits.add(new Hit(
                    hit.id(),
                    longOf(source.get(KnowledgeIndex.fieldDocId())),
                    longOf(source.get(KnowledgeIndex.fieldChunkId())),
                    stringOf(source.get(KnowledgeIndex.fieldTitle())),
                    stringOf(source.get(KnowledgeIndex.fieldText())),
                    0, 0, hit.score() == null ? 0 : hit.score(),
                    rank, -1));
        }
        return hits;
    }

    /** 语义召回。ES 的 cosine 相似度得分落在 0~1，可直接用作证据闸的相关性判据。 */
    private List<Hit> searchByVector(float[] queryVector) throws Exception {
        List<Float> vector = new ArrayList<>(queryVector.length);
        for (float v : queryVector) {
            vector.add(v);
        }

        SearchResponse<Map> response = es.search(s -> s
                        .index(KnowledgeIndex.NAME)
                        .size(candidates)
                        .knn(k -> k
                                .field(KnowledgeIndex.fieldVector())
                                .queryVector(vector)
                                .k(candidates)
                                .numCandidates(Math.max(numCandidates, candidates))),
                Map.class);

        List<Hit> hits = new ArrayList<>();
        int rank = 0;
        for (co.elastic.clients.elasticsearch.core.search.Hit<Map> hit : response.hits().hits()) {
            Map<?, ?> source = hit.source();
            if (source == null) {
                continue;
            }
            rank++;
            hits.add(new Hit(
                    hit.id(),
                    longOf(source.get(KnowledgeIndex.fieldDocId())),
                    longOf(source.get(KnowledgeIndex.fieldChunkId())),
                    stringOf(source.get(KnowledgeIndex.fieldTitle())),
                    stringOf(source.get(KnowledgeIndex.fieldText())),
                    0, hit.score() == null ? 0 : hit.score(), 0,
                    -1, rank));
        }
        return hits;
    }

    /** RRF 融合两路结果。 */
    private List<Hit> fuse(List<Hit> bm25, List<Hit> knn, int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Hit> merged = new LinkedHashMap<>();

        for (Hit hit : bm25) {
            scores.merge(hit.vectorId(), 1.0 / (RRF_K + hit.bm25Rank()), Double::sum);
            merged.putIfAbsent(hit.vectorId(), hit);
        }
        for (Hit hit : knn) {
            scores.merge(hit.vectorId(), 1.0 / (RRF_K + hit.knnRank()), Double::sum);
            // 向量那路才有可信的相关性分数，融合时保留它
            Hit existing = merged.get(hit.vectorId());
            merged.put(hit.vectorId(), existing == null ? hit : withBoth(existing, hit));
        }

        return scores.entrySet().stream()
                .map(e -> {
                    Hit base = merged.get(e.getKey());
                    return new Hit(base.vectorId(), base.docId(), base.chunkId(), base.title(),
                            base.content(), e.getValue(), base.vectorScore(), base.bm25Score(),
                            base.bm25Rank(), base.knnRank());
                })
                .sorted(Comparator.comparingDouble(Hit::rrfScore).reversed())
                .limit(topK)
                .toList();
    }

    /** 把关键词路与向量路的信息合到一条上，避免丢失任一路的名次。 */
    private static Hit withBoth(Hit keywordSide, Hit vectorSide) {
        return new Hit(
                vectorSide.vectorId(), vectorSide.docId(), vectorSide.chunkId(),
                vectorSide.title() != null ? vectorSide.title() : keywordSide.title(),
                vectorSide.content() != null ? vectorSide.content() : keywordSide.content(),
                0, vectorSide.vectorScore(), keywordSide.bm25Score(),
                keywordSide.bm25Rank(), vectorSide.knnRank());
    }

    private static Long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
