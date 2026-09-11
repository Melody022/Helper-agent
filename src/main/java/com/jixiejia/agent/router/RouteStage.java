package com.jixiejia.agent.router;

/**
 * 路由链的 7 步。顺序即短路优先级：越靠前越便宜、越应该先判。
 *
 * <p>设计原则是"确定性优先于模型"：能用规则、缓存、身份判定的，绝不交给模型决定。
 * 模型只在第 5 步参与，且它的输出还要经过枚举校验。
 */
public enum RouteStage {

    /** ① 身份检查：未登录／停用账号直接拦下 */
    IDENTITY("identity", "身份检查"),

    /** ② 系统命令：/reset 这类确定性指令，优先于一切语义理解 */
    COMMAND("command", "系统命令"),

    /** ③ 会话粘性：未过期则复用上一轮 Agent，避免连续追问被反复重分类 */
    STICKY("sticky", "会话粘性"),

    /** ④ 指代消解：把"这个""它"用最近几轮补全 */
    REFERENCE("reference", "指代消解"),

    /** ⑤ 三层意图分类 */
    INTENT("intent", "意图分类"),

    /** ⑥ Agent 匹配：注册表 + 角色权限 + 优先级 */
    AGENT_MATCH("agent_match", "Agent 匹配"),

    /** ⑦ 执行（交由 M5 的 LangGraph4j 图完成） */
    EXECUTE("execute", "执行"),

    /** 转人工：短路入队，不经过 Agent */
    HANDOFF("handoff", "转人工"),

    /** 投诉：固定话术，不经过 Agent */
    COMPLAINT("complaint", "投诉安抚"),

    /** 链路自身出错 */
    ERROR("error", "异常");

    private final String code;
    private final String label;

    RouteStage(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 落库用的短码，与 ai_audit_log.route_stage 对齐。 */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
