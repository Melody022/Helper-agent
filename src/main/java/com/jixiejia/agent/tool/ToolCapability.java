package com.jixiejia.agent.tool;

/**
 * 工具能力标识。与 ai_tool.capability、ai_agent.capabilities 取值保持一致，
 * 是"Agent 能调用哪些工具"的连接点。
 */
public final class ToolCapability {

    /** 设备查询 */
    public static final String EQUIPMENT = "equipment";

    /** 出租（设备招租） */
    public static final String CHUZU = "chuzu";

    /** 求租（机主找活） */
    public static final String QIUZU = "qiuzu";

    /** 用机需求 / 新机询价 */
    public static final String DEMAND = "demand";

    /** 资讯 */
    public static final String NEWS = "news";

    /** 平台规则 / FAQ（M6 知识线使用，非数据库工具） */
    public static final String POLICY = "policy";

    /** 发布出租/求租（M7 工作流使用） */
    public static final String PUBLISH = "publish";

    private ToolCapability() {
    }
}
