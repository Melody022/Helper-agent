package com.jixiejia.agent.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库索引的定义与维护。
 *
 * <p>索引里同时放两样东西，供两种检索方式各取所需：
 * <ul>
 *   <li>{@code textContent} —— 中文用 {@code ik_max_word} 建索引、{@code ik_smart} 查询，
 *       用于关键词（BM25）召回。两个分析器不一致是刻意的：建索引时切得越细召回越高，
 *       查询时切得越粗精度越高，这是 IK 的常规用法；</li>
 *   <li>{@code vector} —— 1024 维稠密向量，cosine 相似度，用于语义（KNN）召回。</li>
 * </ul>
 *
 * <p>为什么要两种：关键词召回对"押金""手续费"这类专有名词准，但用户说
 * "租机器要交多少钱"就匹配不上；向量召回能理解同义说法，但对专有名词不够精确。
 * 两路结果用 RRF 融合，互补。
 *
 * <p>索引不存在时启动自动创建。ES 没启动也不阻断应用——知识库不可用只影响"问规则"，
 * 其余功能照常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndex {

    /** 索引名。刻意不叫 knowledge_base——那是本地已有的另一个项目的索引，不共用。 */
    public static final String NAME = "jxj_knowledge";

    private static final String FIELD_TEXT = "textContent";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_DOC_ID = "docId";
    private static final String FIELD_CHUNK_ID = "chunkId";
    private static final String FIELD_TITLE = "title";

    private static final String ANALYZER_INDEX = "ik_max_word";
    private static final String ANALYZER_SEARCH = "ik_smart";

    private final ElasticsearchClient client;

    @Value("${rag.embedding-dimensions:1024}")
    private int dimensions;

    /**
     * ES 是否可用。启动时探测一次，运行中出问题会由各调用点自己兜。
     * 不能让 ES 没起就卡住整个应用启动。
     */
    private volatile boolean available;

    @PostConstruct
    void init() {
        try {
            ensureIndex();
            this.available = true;
        } catch (Exception e) {
            this.available = false;
            log.warn("Elasticsearch 不可用，知识库检索将降级（其余功能不受影响）：{}", e.toString());
        }
    }

    public boolean available() {
        return available;
    }

    /** 索引不存在则创建。已存在时不动它（不重建、不覆盖数据）。 */
    public void ensureIndex() throws Exception {
        boolean exists = client.indices().exists(e -> e.index(NAME)).value();
        if (exists) {
            log.info("知识库索引 {} 已存在", NAME);
            return;
        }

        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put(FIELD_TEXT, Property.of(p -> p.text(t -> t
                .analyzer(ANALYZER_INDEX)
                .searchAnalyzer(ANALYZER_SEARCH))));
        properties.put(FIELD_TITLE, Property.of(p -> p.text(t -> t
                .analyzer(ANALYZER_INDEX)
                .searchAnalyzer(ANALYZER_SEARCH))));
        properties.put(FIELD_VECTOR, Property.of(p -> p.denseVector(d -> d
                .dims(dimensions)
                .index(true)
                .similarity("cosine"))));
        properties.put(FIELD_DOC_ID, Property.of(p -> p.long_(l -> l)));
        properties.put(FIELD_CHUNK_ID, Property.of(p -> p.long_(l -> l)));

        client.indices().create(c -> c.index(NAME).mappings(m -> m.properties(properties)));
        log.info("知识库索引 {} 已创建（{} 维向量 + IK 中文分词）", NAME, dimensions);
    }

    /** ES 文档 id：文档 id + 块序号，便于按文档整体删除。 */
    public static String vectorId(Long docId, int chunkIndex) {
        return docId + "_" + chunkIndex;
    }

    /** 这些是检索与展示要用的字段名，供 Retriever 引用，避免各处硬编码字符串。 */
    public static String fieldText() {
        return FIELD_TEXT;
    }

    public static String fieldVector() {
        return FIELD_VECTOR;
    }

    public static String fieldDocId() {
        return FIELD_DOC_ID;
    }

    public static String fieldChunkId() {
        return FIELD_CHUNK_ID;
    }

    public static String fieldTitle() {
        return FIELD_TITLE;
    }
}
