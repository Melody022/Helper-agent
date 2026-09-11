package com.jixiejia.agent.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiRole;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.entity.ai.AiUserRole;
import com.jixiejia.agent.persistence.mapper.ai.AiRoleMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 登录与角色解析。
 *
 * <p>登录失败一律只返回 empty，不区分"账号不存在"和"密码错误"——区分了就等于送人一个
 * 账号枚举接口。具体原因只写进服务端日志，方便运维排查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AiUserMapper userMapper;
    private final AiUserRoleMapper userRoleMapper;
    private final AiRoleMapper roleMapper;
    private final TokenStore tokenStore;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 校验账号密码。
     *
     * @return 通过时返回用户与其角色，否则 empty
     */
    public Optional<CurrentUser> authenticate(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null) {
            return Optional.empty();
        }

        AiUser user = userMapper.selectOne(
                Wrappers.<AiUser>lambdaQuery().eq(AiUser::getUsername, username.trim()));

        if (user == null) {
            log.info("登录失败：账号不存在 {}", username);
            return Optional.empty();
        }
        if ("1".equals(user.getStatus())) {
            log.info("登录失败：账号已停用 {}", username);
            return Optional.empty();
        }
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            log.info("登录失败：密码错误 {}", username);
            return Optional.empty();
        }

        return Optional.of(toCurrentUser(user));
    }

    /** 登录成功：校验通过后签发令牌。 */
    public Optional<String> login(String username, String rawPassword) {
        Optional<CurrentUser> user = authenticate(username, rawPassword);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        String token = tokenStore.issue(user.get());
        recordLoginTime(user.get().userId());
        return Optional.of(token);
    }

    /** 按令牌取当前用户。 */
    public Optional<CurrentUser> resolve(String token) {
        return tokenStore.resolve(token);
    }

    public void logout(String token) {
        tokenStore.revoke(token);
    }

    /** 让某用户的全部登录态立即失效（停用账号、调整角色后调用）。 */
    public void kickOut(Long userId) {
        tokenStore.revokeByUserId(userId);
    }

    /** 解析用户的角色标识集合。没有任何角色时给最小权限 USER，避免出现"无角色"这种未定义态。 */
    public Set<String> rolesOf(Long userId) {
        List<AiUserRole> links = userRoleMapper.selectList(
                Wrappers.<AiUserRole>lambdaQuery().eq(AiUserRole::getUserId, userId));
        if (links.isEmpty()) {
            return Set.of(CurrentUser.ROLE_USER);
        }

        List<Long> roleIds = links.stream().map(AiUserRole::getRoleId).toList();
        List<AiRole> roles = roleMapper.selectList(
                Wrappers.<AiRole>lambdaQuery()
                        .in(AiRole::getId, roleIds)
                        .eq(AiRole::getStatus, "0"));

        Set<String> result = new LinkedHashSet<>();
        for (AiRole role : roles) {
            if (role.getRoleKey() != null && !role.getRoleKey().isBlank()) {
                result.add(role.getRoleKey());
            }
        }
        return result.isEmpty() ? Set.of(CurrentUser.ROLE_USER) : result;
    }

    private CurrentUser toCurrentUser(AiUser user) {
        return new CurrentUser(user.getId(), user.getUsername(), user.getNickname(),
                rolesOf(user.getId()));
    }

    private void recordLoginTime(Long userId) {
        try {
            AiUser update = new AiUser();
            update.setId(userId);
            update.setLastLoginTime(LocalDateTime.now());
            userMapper.updateById(update);
        } catch (Exception e) {
            // 记录登录时间失败不该导致登录失败
            log.warn("更新最后登录时间失败：{}", e.toString());
        }
    }
}
