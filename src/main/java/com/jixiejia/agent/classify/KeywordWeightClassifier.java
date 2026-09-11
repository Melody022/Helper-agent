package com.jixiejia.agent.classify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 第 1 层：关键词加权分类。
 *
 * <p>每个意图取"命中的最高权重关键词"作为得分，最高分达到高权重阈值（默认 0.9）
 * <b>且严格高于第二名</b>时才定案。
 *
 * <p>为什么要卡"严格高于第二名"：像"挖掘机出租"这种句子会同时命中设备意图和出租意图。
 * 只看最高分就直出的话，等于用关键词的枚举顺序决定路由，用户会被随机送进 EquipmentAgent
 * 或 RentalAgent。这种歧义应该下沉给模型层判断，而不是在规则层硬猜。
 *
 * <p>这一层的价值不只是省钱：它完全可复现、可回归，冒烟测试里"关键词高置信直出"靠的就是它。
 */
@Slf4j
@Component
public class KeywordWeightClassifier {

    private final double highWeight;
    private final double midWeight;

    public KeywordWeightClassifier(
            @Value("${routing.keyword.high-weight:0.9}") double highWeight,
            @Value("${routing.keyword.mid-weight:0.7}") double midWeight) {
        this.highWeight = highWeight;
        this.midWeight = midWeight;
    }

    /** 打分结果：按得分降序排列的意图，以及各自命中的词。 */
    private record Scored(List<Map.Entry<Intent, Double>> ranked, Map<Intent, List<String>> hits) {
    }

    private Scored score(String text) {
        Map<Intent, Double> scores = new LinkedHashMap<>();
        Map<Intent, List<String>> hits = new LinkedHashMap<>();

        for (Intent intent : Intent.values()) {
            if (intent == Intent.UNKNOWN || intent == Intent.CROSS_DOMAIN) {
                continue;
            }
            double best = 0;
            List<String> matched = new ArrayList<>();

            for (String kw : intent.highKeywords()) {
                if (text.contains(kw)) {
                    best = Math.max(best, highWeight);
                    matched.add(kw);
                }
            }
            for (String kw : intent.midKeywords()) {
                if (text.contains(kw)) {
                    best = Math.max(best, midWeight);
                    matched.add(kw);
                }
            }
            if (best > 0) {
                scores.put(intent, best);
                hits.put(intent, matched);
            }
        }

        List<Map.Entry<Intent, Double>> ranked = scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Intent, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .toList();
        return new Scored(ranked, hits);
    }

    /**
     * 尝试用关键词定案。
     *
     * @return 定案时返回结果，否则 empty（交给下一层）
     */
    public Optional<IntentResult> classify(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Scored scored = score(text);
        if (scored.ranked().isEmpty()) {
            return Optional.empty();
        }

        Map.Entry<Intent, Double> top = scored.ranked().get(0);
        double runnerUp = scored.ranked().size() > 1 ? scored.ranked().get(1).getValue() : 0.0;
        String hitText = String.join("、", scored.hits().get(top.getKey()));

        if (top.getValue() >= highWeight && top.getValue() > runnerUp) {
            log.debug("关键词层定案：{} 命中 [{}]", top.getKey(), hitText);
            return Optional.of(IntentResult.of(top.getKey(), top.getValue(),
                    ClassifyLayer.KEYWORD, "命中关键词：" + hitText));
        }

        log.debug("关键词层不足以定案：最高 {}={}，次高={}", top.getKey(), top.getValue(), runnerUp);
        return Optional.empty();
    }

    /**
     * 关键词层给出的倾向，不论分数够不够。
     * 只作为提示传给模型层——是"参考"不是"约束"，模型可以推翻。
     */
    public Optional<Intent> hint(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Scored scored = score(text);
        return scored.ranked().isEmpty()
                ? Optional.empty()
                : Optional.of(scored.ranked().get(0).getKey());
    }
}
