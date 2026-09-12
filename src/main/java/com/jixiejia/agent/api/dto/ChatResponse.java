package com.jixiejia.agent.api.dto;

import com.jixiejia.agent.chat.ChatService;
import com.jixiejia.agent.rag.KnowledgeAnswerService;

import java.util.List;

/**
 * 对话响应。
 *
 * <p>除了回答本身，还回带意图、置信度、命中的 Agent 与路由终止步骤。
 * 这些是排查"为什么答成这样"的第一手依据，前端也可以选择性地展示。
 *
 * @param sources 答案依据（文档标题 / 章节路径 / 页码 / 原文片段），仅知识线有。
 *                前端据此做溯源——用户能点开看到"这个答案是从哪份文档的哪一节来的"。
 *                没有依据可展示时为空数组。
 */
public record ChatResponse(
        String conversationId,
        String answer,
        String intent,
        double confidence,
        String agentKey,
        String stage,
        boolean shortCircuit,
        List<KnowledgeAnswerService.Source> sources
) {

    public static ChatResponse of(ChatService.ChatResult result) {
        return new ChatResponse(result.conversationId(), result.answer(), result.intent(),
                result.confidence(), result.agentKey(), result.stage(), result.shortCircuit(),
                result.sources());
    }
}
