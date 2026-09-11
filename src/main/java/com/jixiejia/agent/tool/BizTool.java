package com.jixiejia.agent.tool;

/**
 * 查询工具的标记接口。实现了本接口的 Bean 会被
 * {@link ToolRegistry} 自动发现并注册，无需在任何地方手工登记。
 *
 * <p>新增一个工具只需要两步：实现本接口 + 在方法上标注 {@code @Tool}。
 *
 * <p>之所以用标记接口而不是扫包找 {@code @Tool}：能力标识（capability）是编译期就能确定的信息，
 * 写成方法由实现类显式声明，比从类名反推可靠得多——类改名不会悄悄改变权限归属。
 */
public interface BizTool {

    /** 该工具类提供的能力标识，取值见 {@link ToolCapability}。 */
    String capability();

    /** 展示名，用于管理台与 ai_tool.display_name。 */
    default String displayName() {
        return getClass().getSimpleName();
    }
}
