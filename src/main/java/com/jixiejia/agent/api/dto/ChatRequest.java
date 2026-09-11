package com.jixiejia.agent.api.dto;

/**
 * 对话请求。
 *
 * @param conversationId 会话标识；不传则由服务端生成并在响应里返回，
 *                       后续轮次要带上同一个 id，会话粘性和多轮上下文都靠它
 * @param message        用户这句话
 */
public record ChatRequest(String conversationId, String message) {
}
