package com.jixiejia.agent.api.dto;

import com.jixiejia.agent.auth.CurrentUser;

import java.util.Set;

/**
 * 当前用户信息。刻意不返回密码、手机号等敏感字段。
 */
public record UserInfo(
        Long id,
        String username,
        String nickname,
        Set<String> roles,
        boolean admin
) {

    public static UserInfo of(CurrentUser user) {
        return new UserInfo(user.userId(), user.username(), user.displayName(),
                user.roles(), user.isAdmin());
    }
}
