package com.jixiejia.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 */
public record LoginRequest(
        @NotBlank(message = "账号不能为空") String username,
        @NotBlank(message = "密码不能为空") String password
) {
}
