package com.jixiejia.agent.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 一个职责单一的业务 Agent。
 *
 * <p>实现类与 {@code ai_agent} 表里的行一一对应：{@link #agentKey()} 就是
 * {@code ai_agent.agent_key}，能力集合写在库里由 {@link com.jixiejia.agent.router.AgentRegistry}
 * 匹配，工具白名单也由注册表算好后传进来——Agent 自己不关心"我能用哪些工具"，
 * 拿到什么就用什么。这样加一个 Agent 只需要写一个类，不用改路由和权限代码。
 */
public interface BizAgent {

    /** Agent 标识，取值与 ai_agent.agent_key 对齐。 */
    String agentKey();

    /**
     * 执行一轮对话。
     *
     * @param userText 用户这句话（已完成指代消解）
     * @param history  最近若干轮对话，按时间正序，可为空
     * @param tools    本轮允许该 Agent 使用的工具（已按角色白名单过滤）
     * @return 最终回答；执行失败时返回可读的兜底话术，不抛异常
     */
    String reply(String userText, List<Message> history, List<ToolCallback> tools);
}
