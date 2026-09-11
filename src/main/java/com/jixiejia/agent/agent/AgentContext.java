package com.jixiejia.agent.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 一轮 Agent 执行需要的全部输入。
 *
 * <p>收成一个对象而不是铺开成参数列表，是因为需要的东西会随功能增长：
 * 一开始只有"用户说了什么"，后来发布工作流需要会话 id、鉴权需要用户身份。
 * 每加一项就改一次接口、改一遍所有实现和调用点，代价太大；
 * 收成一个上下文对象之后，新增字段不影响已有实现。
 *
 * @param conversationId 会话标识。有状态的 Agent（比如发布要按会话存草稿）需要它
 * @param userId         ai_user.id
 * @param memberId       映射到的平台会员 id，未绑定时为 null
 * @param roleKey        角色标识，用于工具白名单
 * @param userText       用户这句话（已完成指代消解）
 * @param history        最近若干轮对话，按时间正序，可为空
 * @param tools          本轮允许该 Agent 使用的工具（已按角色白名单过滤）
 */
public record AgentContext(
        String conversationId,
        Long userId,
        Long memberId,
        String roleKey,
        String userText,
        List<Message> history,
        List<ToolCallback> tools
) {

    /** 只带用户输入和工具的简化构造，供测试与无状态 Agent 使用。 */
    public static AgentContext of(String userText, List<ToolCallback> tools) {
        return new AgentContext(null, null, null, "USER", userText, List.of(), tools);
    }
}
