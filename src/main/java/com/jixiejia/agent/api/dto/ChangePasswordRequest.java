package com.jixiejia.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改自己的密码。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "请填写原密码") String oldPassword,

        @NotBlank(message = "请填写新密码")
        @Size(min = 6, message = "新密码至少 6 位")
        String newPassword
) {
}
