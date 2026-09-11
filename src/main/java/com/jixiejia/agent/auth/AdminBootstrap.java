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
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;

/**
 * 首次启动时创建一个管理员账号。
 *
 * <p>为什么用代码而不是往 SQL 脚本里塞一段 BCrypt 哈希：
 * 哈希写死在脚本里等于把默认口令公开发布，而且换密码就得改脚本。
 * 这里改成——没有管理员才创建，密码优先取配置，没配就随机生成并打印到日志。
 *
 * <p>已经有任意一个 ADMIN 账号时直接跳过，不会覆盖既有账号或重置密码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements SmartInitializingSingleton {

    /** 随机密码长度 */
    private static final int RANDOM_PASSWORD_LENGTH = 12;

    private static final String RANDOM_CHARS =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final AiUserMapper userMapper;
    private final AiUserRoleMapper userRoleMapper;
    private final AiRoleMapper roleMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${ai.auth.init-admin-username:admin}")
    private String initAdminUsername;

    @Value("${ai.auth.init-admin-password:}")
    private String initAdminPassword;

    @Override
    public void afterSingletonsInstantiated() {
        try {
            bootstrap();
        } catch (Exception e) {
            // 建不出管理员不该阻断启动：可能是库还没建好，启动后可以重跑
            log.warn("管理员账号初始化失败（不影响启动）：{}", e.toString());
        }
    }

    private void bootstrap() {
        AiRole adminRole = roleMapper.selectOne(
                Wrappers.<AiRole>lambdaQuery().eq(AiRole::getRoleKey, CurrentUser.ROLE_ADMIN));
        if (adminRole == null) {
            log.warn("ai_role 中没有 ADMIN 角色，跳过管理员初始化（请检查 V1 脚本是否执行过）");
            return;
        }

        if (hasAnyAdmin(adminRole.getId())) {
            log.debug("已存在管理员账号，跳过初始化");
            return;
        }

        boolean generated = initAdminPassword == null || initAdminPassword.isBlank();
        String password = generated ? randomPassword() : initAdminPassword;

        AiUser user = new AiUser();
        user.setUsername(initAdminUsername);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname("管理员");
        user.setStatus("0");
        user.setDelFlag("0");
        user.setRemark("系统初始化创建，请尽快修改密码");
        userMapper.insert(user);

        AiUserRole link = new AiUserRole();
        link.setUserId(user.getId());
        link.setRoleId(adminRole.getId());
        userRoleMapper.insert(link);

        if (generated) {
            // 只在这里打印一次，之后不再留痕
            log.warn("""

                    =========================================================
                      已创建初始管理员账号，请立即登录并修改密码
                      账号：{}
                      密码：{}
                    =========================================================
                    """, initAdminUsername, password);
        } else {
            log.info("已按配置创建初始管理员账号：{}", initAdminUsername);
        }
    }

    /** 是否已经存在任意一个管理员账号。 */
    private boolean hasAnyAdmin(Long adminRoleId) {
        List<AiUserRole> links = userRoleMapper.selectList(
                Wrappers.<AiUserRole>lambdaQuery().eq(AiUserRole::getRoleId, adminRoleId));
        if (links.isEmpty()) {
            return false;
        }
        List<Long> userIds = links.stream().map(AiUserRole::getUserId).toList();
        Long count = userMapper.selectCount(
                Wrappers.<AiUser>lambdaQuery().in(AiUser::getId, userIds));
        return count != null && count > 0;
    }

    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(RANDOM_PASSWORD_LENGTH);
        for (int i = 0; i < RANDOM_PASSWORD_LENGTH; i++) {
            sb.append(RANDOM_CHARS.charAt(random.nextInt(RANDOM_CHARS.length())));
        }
        return sb.toString();
    }
}
