package com.jixiejia.agent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 精排（rerank）打分：给"用户问题 + 候选资料"这一对打一个相关性分。
 *
 * <p><b>为什么需要它。</b>向量召回的相似度是"问题向量和资料向量各自算好再比"，
 * 问题和资料之间没有交互——所以它对"两个向量恰好在同一片区域"很敏感，
 * 而对"资料里到底有没有回答这个问题"不敏感。实测语料里所有的最高相似度都挤在
 * 0.63~0.93 这个窄区间，能不能答和不能答的问题几乎完全重叠，单靠它分不开。
 *
 * <p>精排是交叉编码器：把问题和资料<b>拼在一起</b>过一遍模型，输出一个相关性分。
 * 实测同一批样本上，有答案的问题中位数 0.837，没答案的中位数 0.130，
 * 两类之间留出了很宽的空隙，这才是证据闸真正需要的判据。
 *
 * <p><b>为什么放在检索之后而不是替代检索。</b>精排要对每个候选单独跑一次前向，
 * 代价和候选数成正比，不可能对全库跑。所以链路是
 * "混合召回收窄候选 → 精排给这几十条打分 → 证据闸按分判定"。
 *
 * <p><b>拿不到分怎么办。</b>精排是外部服务，会超时、会限流。返回 {@code null}
 * 表示"这次没拿到精排分"，由证据闸退回到向量相似度判据——宁可退化，不能让
 * 知识问答整条不可用。
 */
@Slf4j
@Component
public class RerankScorer implements RerankFunction {

    /**
     * DashScope 原生精排接口。注意它和 embedding 用的 compatible-mode 不是同一个路径前缀：
     * embedding 在 {@code /compatible-mode/v1}，精排在 {@code /api/v1/services/rerank/...}。
     */
    private static final String RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";

    private final ObjectMapper json = JsonMapper.builder().build();

    private final String model;
    private final String apiKey;
    private final String baseUrl;

    /** 精排打分不超过候选集大小，但也要有个上限，避免一次请求体过大 */
    @Value("${rag.rerank.max-documents:20}")
    private int maxDocuments;

    private RestClient restClient;

    public RerankScorer(@Value("${rag.rerank.model:gte-rerank-v2}") String model,
                        @Value("${rag.rerank.api-key:}") String apiKey,
                        @Value("${rag.rerank.base-url:https://dashscope.aliyuncs.com}") String baseUrl) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /** 配置齐全才启用。缺 api-key 时整个精排环节跳过，退回向量判据。 */
    @Override
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 给候选资料逐个打相关性分。
     *
     * @return 与 {@code documents} 一一对应的分数；不可用或调用失败时返回 {@code null}
     *         （调用方据此退回向量判据）
     */
    @Override
    public List<Double> scoreAll(String query, List<String> documents) {
        if (!configured() || query == null || query.isBlank()
                || documents == null || documents.isEmpty()) {
            return null;
        }

        List<String> docs = documents.size() > maxDocuments
                ? documents.subList(0, maxDocuments)
                : documents;

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", Map.of("query", query, "documents", docs));

            String raw = client().post()
                    .uri(RERANK_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(json.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            return parse(raw, docs.size());

        } catch (Exception e) {
            // 精排失败不是致命错误：证据闸会退回向量判据，只是判得糙一点
            log.warn("精排调用失败，退回向量相关度判据：{}", e.toString());
            return null;
        }
    }

    /**
     * 解析精排响应。
     *
     * <p>返回结构是 {@code {"output":{"results":[{"index":0,"relevance_score":0.53}, ...]}}}，
     * 其中 {@code index} 是候选在请求数组里的下标，不保证按分数排序、也不保证个数一致，
     * 所以这里按下标回填，缺的位置填 0。
     */
    private List<Double> parse(String raw, int size) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonNode root = json.readTree(raw);
        JsonNode results = root.path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            log.warn("精排响应里没有 results：{}", abbreviate(raw));
            return null;
        }

        List<Double> scores = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            scores.add(0.0);
        }
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index >= 0 && index < size) {
                scores.set(index, item.path("relevance_score").asDouble(0.0));
            }
        }
        return scores;
    }

    /** 懒建，避免没配 api-key 时也建一个 HTTP 客户端。 */
    private RestClient client() {
        if (restClient == null) {
            restClient = RestClient.builder().baseUrl(baseUrl).build();
        }
        return restClient;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
