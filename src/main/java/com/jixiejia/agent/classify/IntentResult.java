package com.jixiejia.agent.classify;

import java.util.Set;

/**
 * 意图分类结果。
 *
 * @param intent     识别出的意图
 * @param confidence 置信度 0~1
 * @param layer      在哪一层定案的
 * @param reason     判定依据（关键词命中了什么 / 模型的原始输出），用于审计排查
 * @param domains    跨域时模型指出的领域标识集合；非跨域为空
 */
public record IntentResult(Intent intent, double confidence, ClassifyLayer layer,
                           String reason, Set<String> domains) {

    public static IntentResult of(Intent intent, double confidence, ClassifyLayer layer, String reason) {
        return new IntentResult(intent, confidence, layer, reason, Set.of());
    }

    public static IntentResult of(Intent intent, double confidence, ClassifyLayer layer,
                                  String reason, Set<String> domains) {
        return new IntentResult(intent, confidence, layer, reason,
                domains == null ? Set.of() : Set.copyOf(domains));
    }

    /** 兜底结果：三层都没定案。 */
    public static IntentResult unknown(String reason) {
        return new IntentResult(Intent.UNKNOWN, 0.0, ClassifyLayer.FALLBACK, reason, Set.of());
    }

    /** 是否为跨域且指明了领域。 */
    public boolean hasDomains() {
        return intent == Intent.CROSS_DOMAIN && !domains.isEmpty();
    }
}
