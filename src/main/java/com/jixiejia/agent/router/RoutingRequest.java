package com.jixiejia.agent.router;

/**
 * 一次路由请求的输入。
 *
 * @param conversationId 会话标识（LangGraph4j threadId）
 * @param aiUserId       AI 侧账号 id，未登录为 null
 * @param memberId       映射到的平台会员 id，未绑定为 null
 * @param roleKey        角色标识 USER / ADMIN，未登录时按 USER 处理
 * @param message        用户原始输入
 */
public record RoutingRequest(
        String conversationId,
        Long aiUserId,
        Long memberId,
        String roleKey,
        String message
) {

    /** 未登录/未指定角色时按最小权限的 USER 处理。 */
    public String effectiveRoleKey() {
        return (roleKey == null || roleKey.isBlank()) ? "USER" : roleKey;
    }
}
