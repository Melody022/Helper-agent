package com.jixiejia.agent.rag.parse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 {@code lit is-complex --compact} 的输出。
 *
 * <p>单独抽出来是为了能脱离进程单测：真正的 JSON 解析逻辑不该只能靠"起一个 lit 进程"
 * 才能验证到。
 *
 * <p>输入形如：
 * <pre>
 * [{"pageNumber":1,"textLength":2988,"textCoverage":0.25,
 *   "needsOcr":true,"reasons":["embedded-images"], ...}]
 * </pre>
 * 这里只取分流用得上的四个字段，其余（版面统计等）忽略。
 */
final class PageInfoParser {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private PageInfoParser() {
    }

    static List<LiteParseClient.PageInfo> parse(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            throw new IllegalArgumentException("解析工具没有返回内容");
        }

        JsonNode root = JSON.readTree(stdout.trim());
        if (!root.isArray()) {
            throw new IllegalArgumentException("解析结果不是预期的数组格式");
        }

        List<LiteParseClient.PageInfo> pages = new ArrayList<>();
        for (JsonNode node : root) {
            List<String> reasons = new ArrayList<>();
            JsonNode reasonsNode = node.path("reasons");
            if (reasonsNode.isArray()) {
                for (JsonNode r : reasonsNode) {
                    reasons.add(r.asString(""));
                }
            }
            pages.add(new LiteParseClient.PageInfo(
                    node.path("pageNumber").asInt(pages.size() + 1),
                    node.path("textCoverage").asDouble(0.0),
                    node.path("needsOcr").asBoolean(false),
                    reasons));
        }
        return pages;
    }
}
