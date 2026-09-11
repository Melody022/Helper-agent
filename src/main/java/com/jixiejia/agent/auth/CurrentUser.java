package com.jixiejia.agent.auth;

import java.util.Set;

/**
 * 当前登录用户。
 *
 * @param userId   ai_user.id
 * @param username 登录账号
 * @param nickname 昵称
 * @param roles    角色标识集合，如 ["USER"] / ["ADMIN"]
 */
public record CurrentUser(Long userId, String username, String nickname, Set<String> roles) {

    /** 管理员角色标识，与 ai_role.role_key 对齐。 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 普通用户角色标识。 */
    public static final String ROLE_USER = "USER";

    public boolean isAdmin() {
        return roles != null && roles.contains(ROLE_ADMIN);
    }

    public boolean hasRole(String roleKey) {
        return roles != null && roles.contains(roleKey);
    }

    /** 展示名，昵称优先，没有就用账号。 */
    public String displayName() {
        return (nickname == null || nickname.isBlank()) ? username : nickname;
    }
}
