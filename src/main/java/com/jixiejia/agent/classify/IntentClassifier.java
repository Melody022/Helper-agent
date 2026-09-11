package com.jixiejia.agent.classify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 三层意图分类的编排：关键词加权 → Ollama 小模型 → MIMO 大模型。
 *
 * <p>短路顺序是从便宜到贵：关键词零成本且可复现，小模型便宜，大模型最贵。
 * 任何一层给出可信结论就立刻返回，不再往下走。
 *
 * <p>关于"可信"的判据：模型自报的 confidence 只是参考，不能全信——
 * 小模型经常对错误答案给出 0.95。所以每层还额外要求意图不能是 UNKNOWN，
 * 并且达到该层自己的最低置信阈值（可配，便于用评估集校准）。
 */
@Slf4j
@Component
public class IntentClassifier {

    private final KeywordWeightClassifier keywordClassifier;
    private final SmallModelClassifier smallModelClassifier;
    private final LlmClassifier llmClassifier;

    /** 小模型给出多高的置信度才采信 */
    private final double smallMinConfidence;

    /** 大模型给出多高的置信度才采信；比小模型低，因为它是最后一层，再拦就只能 UNKNOWN 了 */
    private final double llmMinConfidence;

    /** 关掉后两层，只跑关键词，用于离线回归与降本 */
    private final boolean modelLayersEnabled;

    public IntentClassifier(KeywordWeightClassifier keywordClassifier,
                            SmallModelClassifier smallModelClassifier,
                            LlmClassifier llmClassifier,
                            @Value("${routing.small-model.min-confidence:0.6}") double smallMinConfidence,
                            @Value("${routing.llm.min-confidence:0.4}") double llmMinConfidence,
                            @Value("${routing.model-layers-enabled:true}") boolean modelLayersEnabled) {
        this.keywordClassifier = keywordClassifier;
        this.smallModelClassifier = smallModelClassifier;
        this.llmClassifier = llmClassifier;
        this.smallMinConfidence = smallMinConfidence;
        this.llmMinConfidence = llmMinConfidence;
        this.modelLayersEnabled = modelLayersEnabled;
    }

    /**
     * 对一句话做意图分类。
     *
     * @param userText    用户当前这句（调用方应先做指代消解）
     * @param recentTurns 最近几轮对话文本，可空
     */
    public IntentResult classify(String userText, String recentTurns) {
        // ---- 第 1 层：关键词加权 ----
        Optional<IntentResult> byKeyword = keywordClassifier.classify(userText);
        if (byKeyword.isPresent()) {
            return byKeyword.get();
        }

        if (!modelLayersEnabled) {
            return IntentResult.unknown("关键词未命中，且模型层已关闭");
        }

        String hint = keywordClassifier.hint(userText)
                .map(Enum::name)
                .orElse(null);

        // ---- 第 2 层：本地小模型 ----
        Optional<IntentResult> bySmall = smallModelClassifier.classify(userText, recentTurns, hint);
        if (isTrustworthy(bySmall, smallMinConfidence)) {
            return bySmall.get();
        }

        // ---- 第 3 层：大模型 ----
        Optional<IntentResult> byLlm = llmClassifier.classify(userText, recentTurns, hint);
        if (isTrustworthy(byLlm, llmMinConfidence)) {
            return byLlm.get();
        }

        // 三层都没定案。可能是模型不可用，也可能是真的判不出来，
        // 两种都走兜底，但 reason 要能区分，方便排查是"模型挂了"还是"用户说得怪"。
        String reason = describeFallback(bySmall, byLlm);
        log.info("三层分类均未定案，落入兜底：{}", reason);
        return IntentResult.unknown(reason);
    }

    private boolean isTrustworthy(Optional<IntentResult> result, double minConfidence) {
        return result.isPresent()
                && result.get().intent() != Intent.UNKNOWN
                && result.get().confidence() >= minConfidence;
    }

    private String describeFallback(Optional<IntentResult> bySmall, Optional<IntentResult> byLlm) {
        if (bySmall.isEmpty() && byLlm.isEmpty()) {
            return "小模型与大模型均不可用或未返回可解析结果";
        }
        return "模型置信度不足：" + bySmall.map(r -> "小模型=" + r.intent() + "/" + r.confidence())
                .orElse("小模型=无")
                + "，"
                + byLlm.map(r -> "大模型=" + r.intent() + "/" + r.confidence())
                .orElse("大模型=无");
    }
}
