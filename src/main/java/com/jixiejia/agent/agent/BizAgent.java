package com.jixiejia.agent.agent;

/**
 * 一个职责单一的业务 Agent。
 *
 * <p>实现类与 {@code ai_agent} 表里的行一一对应：{@link #agentKey()} 就是
 * {@code ai_agent.agent_key}，能力集合写在库里由 {@link com.jixiejia.agent.router.AgentRegistry}
 * 匹配，工具白名单也由注册表算好后放进 {@link AgentContext} 传进来——
 * Agent 自己不关心"我能用哪些工具"，拿到什么就用什么。
 * 这样加一个 Agent 只需要写一个类，不用改路由和权限代码。
 */
public interface BizAgent {

    /** Agent 标识，取值与 ai_agent.agent_key 对齐。 */
    String agentKey();

    /**
     * 执行一轮对话。
     *
     * @param context 本轮的全部输入（用户输入、会话、身份、工具）
     * @return 最终回答；执行失败时返回可读的兜底话术，不抛异常
     */
    String reply(AgentContext context);
}
