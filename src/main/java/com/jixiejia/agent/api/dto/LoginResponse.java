package com.jixiejia.agent.api.dto;

/**
 * 登录响应。
 *
 * @param token 后续请求放在 {@code Authorization: Bearer <token>}
 * @param user  当前用户信息
 */
public record LoginResponse(String token, UserInfo user) {
}
