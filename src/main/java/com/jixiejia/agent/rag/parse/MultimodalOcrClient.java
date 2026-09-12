package com.jixiejia.agent.rag.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用多模态大模型给扫描页做 OCR。
 *
 * <p><b>为什么不用 LiteParse 内置的 Tesseract</b>：它要去 GitHub 下载语言包（本机到不了），
 * 而且默认只装 {@code eng}——中文文档一个字都认不出来。多模态模型对中文、
 * 表格、混排版面的识别质量高得多，代价是按量计费。
 *
 * <p><b>为什么不用图像向量化（方案 B）</b>：本次要处理的是国标这类<b>带大量数字和表格</b>的
 * 文档，图像向量召回不稳定、数字容易认错，不如"OCR 成文字再走文本检索"可控。
 * 图像向量只适合"图本身就是答案"的场景。
 *
 * <p>调用方式照 {@code RerankScorer}：直接打 DashScope 的 OpenAI 兼容端点，
 * 复用同一个 {@code DASHSCOPE_API_KEY}。失败返回 {@code null}，由调用方降级——
 * OCR 挂掉不该让整篇文档入库失败。
 */
@Slf4j
@Component
public class MultimodalOcrClient {

    private static final String CHAT_PATH = "/compatible-mode/v1/chat/completions";

    /**
     * 让模型只做"抄写"这件事。
     *
     * <p>刻意强调"不要总结、不要解释、保持表格结构"：OCR 的产物要拿去检索和喂给下游模型，
     * 一旦模型自作主张摘要，原文里的数字和条款就没了——而这些恰恰是要查的东西。
     */
    private static final String OCR_PROMPT = """
            请把这张图片里的文字原样提取出来，输出 markdown。要求：
            1. 不要总结、不要解释、不要补充图片里没有的内容；
            2. 表格输出成 markdown 表格，保持行列对应关系；
            3. 公式、编号、单位原样保留；
            4. 只输出提取到的内容本身，不要任何前后缀说明。
            """;

    private final ObjectMapper json = JsonMapper.builder().build();

    private final String model;
    private final String apiKey;
    private final String baseUrl;

    @Value("${rag.parse.ocr.timeout-ms:120000}")
    private long readTimeoutMs;

    @Value("${rag.parse.ocr.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    private RestClient restClient;

    public MultimodalOcrClient(@Value("${rag.parse.ocr.model:qwen-vl-max}") String model,
                               @Value("${rag.parse.ocr.api-key:}") String apiKey,
                               @Value("${rag.parse.ocr.base-url:https://dashscope.aliyuncs.com}") String baseUrl) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /** 配了 api-key 才启用。没配就只走文本层，扫描件会解析出空内容。 */
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 识别一张图。
     *
     * @param image 图片字节（PNG）
     * @return 提取出的 markdown 文本；失败时返回 {@code null}
     */
    public String ocr(byte[] image) {
        if (!configured() || image == null || image.length == 0) {
            return null;
        }
        try {
            String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(image);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(
                            Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)),
                            Map.of("type", "text", "text", OCR_PROMPT)))));
            body.put("max_tokens", 4000);

            String raw = client().post()
                    .uri(CHAT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(json.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            return extractContent(raw);

        } catch (Exception e) {
            log.warn("多模态 OCR 失败，该页将没有文字：{}", e.toString());
            return null;
        }
    }

    private String extractContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonNode root = json.readTree(raw);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            log.warn("多模态响应里没有 content：{}", abbreviate(raw));
            return null;
        }
        String text = content.asString("");
        return text.isBlank() ? null : text;
    }

    /**
     * 懒建客户端。
     *
     * <p><b>超时必须显式设置</b>。最初这里只声明了 {@code timeout-ms} 配置却没接到
     * RestClient 上，用的是默认超时——200 DPI 的 A4 整页 base64 有好几 MB，
     * 上传加推理根本来不及，实测 21 页里 14 页都在 {@code ReadTimeoutException} 上挂了，
     * 现象是"OCR 莫名其妙没识别出内容"，很容易误判成模型不行。
     */
    private RestClient client() {
        if (restClient == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setReadTimeout(Math.toIntExact(readTimeoutMs));
            restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        }
        return restClient;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
