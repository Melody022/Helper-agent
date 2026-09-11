package com.jixiejia.agent.api.dto;

import com.jixiejia.agent.chat.ChatService;

/**
 * 对话响应。
 *
 * <p>除了回答本身，还回带意图、置信度、命中的 Agent 与路由终止步骤。
 * 这些是排查"为什么答成这样"的第一手依据，前端也可以选择性地展示。
 */
public record ChatResponse(
        String conversationId,
        String answer,
        String intent,
        double confidence,
        String agentKey,
        String stage,
        boolean shortCircuit
) {

    public static ChatResponse of(ChatService.ChatResult result) {
        return new ChatResponse(result.conversationId(), result.answer(), result.intent(),
                result.confidence(), result.agentKey(), result.stage(), result.shortCircuit());
    }
}
