package com.jixiejia.agent.classify;

/**
 * 意图分类命中的层，会落进 ai_audit_log.classify_layer。
 *
 * <p>分层短路是路由链的核心：便宜的先算，便宜的能定案就不惊动贵的。
 */
public enum ClassifyLayer {

    /** 第 1 层：关键词加权。零成本、可复现，高权重命中即直出。 */
    KEYWORD("keyword"),

    /** 第 2 层：本地 Ollama 小模型。便宜、够用，处理关键词覆盖不到的说法。 */
    SMALL_MODEL("small"),

    /** 第 3 层：MIMO 大模型。最贵，只在前面都没定案时兜底。 */
    LLM("llm"),

    /** 三层都没给出可信结果，落到 UNKNOWN。 */
    FALLBACK("fallback");

    private final String code;

    ClassifyLayer(String code) {
        this.code = code;
    }

    /** 落库用的短码，与 ai_audit_log.classify_layer 对齐。 */
    public String code() {
        return code;
    }
}
