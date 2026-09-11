package com.jixiejia.agent.api.dto;

/**
 * 绑定 AI 账号与平台会员。
 *
 * @param aiUserId ai_user.id
 * @param memberId ums_member.id
 */
public record PublishBindingRequest(Long aiUserId, Long memberId) {
}
