package com.jixiejia.agent.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiRole;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.entity.ai.AiUserRole;
import com.jixiejia.agent.persistence.mapper.ai.AiRoleMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserRoleMapper;
import com.jixiejia.agent.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权与角色隔离测试。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li>登录链路本身——密码校验、令牌签发/失效、"不泄露账号是否存在"；</li>
 *   <li><b>管理员和普通用户确实隔离</b>——普通用户拿不到管理接口，
 *       未登录拿不到任何接口。这是"分角色"最容易嘴上说说、实际漏掉的地方，
 *       所以直接用 HTTP 打接口来验，而不是只测 Service。</li>
 * </ol>
 */
@SpringBootTest
class AuthTest {

    private static final String PASSWORD = "Test#123456";

    /**
     * Spring Boot 4.1 没有随包提供 {@code @AutoConfigureMockMvc}（该自动配置未随 4.x 发布），
     * 所以这里从 WebApplicationContext 手工构建。效果等价：DispatcherServlet 的
     * HandlerInterceptor 照样会执行，鉴权拦截器因此能被真实地验证到。
     */
    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private AiUserMapper userMapper;

    @Autowired
    private AiUserRoleMapper userRoleMapper;

    @Autowired
    private AiRoleMapper roleMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long adminUserId;
    private Long normalUserId;
    private String adminUsername;
    private String normalUsername;

    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @BeforeEach
    void createAccounts() {
        adminUsername = "test_admin_" + UUID.randomUUID();
        normalUsername = "test_user_" + UUID.randomUUID();
        adminUserId = createUser(adminUsername, CurrentUser.ROLE_ADMIN);
        normalUserId = createUser(normalUsername, CurrentUser.ROLE_USER);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : new Long[]{adminUserId, normalUserId}) {
            if (id == null) {
                continue;
            }
            tokenStore.revokeByUserId(id);
            userRoleMapper.delete(Wrappers.<AiUserRole>lambdaQuery().eq(AiUserRole::getUserId, id));
            userMapper.deleteById(id);
        }
        // 上面的 deleteById 走 @TableLogic 逻辑删除，行还在表里；
        // 这里补一次物理删除，避免测试账号在开发库里越积越多。
        jdbcTemplate.update("DELETE FROM ai_user WHERE LEFT(username, 5) = 'test_'");
    }

    private Long createUser(String username, String roleKey) {
        AiUser user = new AiUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setStatus("0");
        user.setDelFlag("0");
        userMapper.insert(user);

        AiRole role = roleMapper.selectOne(
                Wrappers.<AiRole>lambdaQuery().eq(AiRole::getRoleKey, roleKey));
        assertThat(role).as("ai_role 里应当有 " + roleKey).isNotNull();

        AiUserRole link = new AiUserRole();
        link.setUserId(user.getId());
        link.setRoleId(role.getId());
        userRoleMapper.insert(link);

        return user.getId();
    }

    private String loginAndGetToken(String username) {
        return authService.login(username, PASSWORD).orElseThrow();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---------------- 登录本身 ----------------

    @Test
    @DisplayName("登录：正确的账号密码签发令牌，错误的都拒绝")
    void loginSucceedsAndFails() {
        Optional<String> token = authService.login(adminUsername, PASSWORD);
        assertThat(token).isPresent();
        assertThat(tokenStore.resolve(token.get())).isPresent();

        assertThat(authService.login(adminUsername, "wrong-password")).isEmpty();
        assertThat(authService.login("no-such-user", PASSWORD)).isEmpty();
        assertThat(authService.login(adminUsername, null)).isEmpty();
    }

    @Test
    @DisplayName("角色解析：管理员拿到 ADMIN，普通用户拿到 USER")
    void rolesResolved() {
        CurrentUser admin = authService.resolve(loginAndGetToken(adminUsername)).orElseThrow();
        CurrentUser normal = authService.resolve(loginAndGetToken(normalUsername)).orElseThrow();

        assertThat(admin.roles()).contains(CurrentUser.ROLE_ADMIN);
        assertThat(admin.isAdmin()).isTrue();

        assertThat(normal.roles()).contains(CurrentUser.ROLE_USER);
        assertThat(normal.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("停用账号不能登录")
    void disabledAccountCannotLogin() {
        AiUser update = new AiUser();
        update.setId(normalUserId);
        update.setStatus("1");
        userMapper.updateById(update);

        assertThat(authService.login(normalUsername, PASSWORD)).isEmpty();
    }

    @Test
    @DisplayName("注销后令牌立即失效")
    void logoutRevokesToken() {
        String token = loginAndGetToken(normalUsername);
        assertThat(authService.resolve(token)).isPresent();

        authService.logout(token);
        assertThat(authService.resolve(token)).isEmpty();
    }

    @Test
    @DisplayName("踢下线：停用/改角色后该用户所有令牌一次性失效")
    void kickOutInvalidatesAllTokens() {
        String t1 = loginAndGetToken(normalUsername);
        String t2 = loginAndGetToken(normalUsername);
        assertThat(authService.resolve(t1)).isPresent();
        assertThat(authService.resolve(t2)).isPresent();

        // 改角色后必须立刻生效，不能等 TTL 过期——否则被降权的用户还能用旧令牌操作
        authService.kickOut(normalUserId);

        assertThat(authService.resolve(t1)).isEmpty();
        assertThat(authService.resolve(t2)).isEmpty();
    }

    // ---------------- 接口层的登录门槛 ----------------

    @Test
    @DisplayName("接口：未登录一律 401")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/admin/tools")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/tools").header("Authorization", bearer("bogus-token")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("接口：登录接口与健康检查无需令牌")
    void publicEndpointsAreOpen() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());

        // 登录接口本身当然不能要求登录；密码错也应是 401（业务失败），不是被拦截
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("接口：登录成功后可查当前用户")
    void meReturnsCurrentUser() throws Exception {
        String token = loginAndGetToken(normalUsername);

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(normalUsername))
                .andExpect(jsonPath("$.admin").value(false));
    }

    // ---------------- 管理员与普通用户的隔离 ----------------

    @Test
    @DisplayName("隔离：普通用户访问管理接口被 403 拒绝")
    void normalUserCannotAccessAdminApi() throws Exception {
        String token = loginAndGetToken(normalUsername);

        mockMvc.perform(get("/api/admin/tools").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/tools/roles").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("隔离：管理员可以访问管理接口")
    void adminCanAccessAdminApi() throws Exception {
        String token = loginAndGetToken(adminUsername);

        mockMvc.perform(get("/api/admin/tools").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools").isArray());

        mockMvc.perform(get("/api/admin/tools/roles").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roleKey").exists());
    }

    // ---------------- 白名单确实生效 ----------------

    @Test
    @DisplayName("白名单：撤掉 USER 的工具授权后，该角色立刻拿不到这些工具")
    void revokingRoleToolsTakesEffectImmediately() throws Exception {
        String token = loginAndGetToken(adminUsername);
        Set<String> before = toolRegistry.toolNamesForRole(CurrentUser.ROLE_USER);
        assertThat(before).isNotEmpty();

        try {
            // 只给 USER 留一个搜索工具
            mockMvc.perform(put("/api/admin/tools/roles/USER")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"toolNames\":[\"search_equipment\"]}"))
                    .andExpect(status().isOk());

            assertThat(toolRegistry.toolNamesForRole(CurrentUser.ROLE_USER))
                    .containsExactly("search_equipment");
        } finally {
            // 恢复原状，避免影响其它测试类
            mockMvc.perform(put("/api/admin/tools/roles/USER")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"toolNames\":" + toJsonArray(before) + "}"))
                    .andExpect(status().isOk());
        }

        assertThat(toolRegistry.toolNamesForRole(CurrentUser.ROLE_USER)).isEqualTo(before);
    }

    @Test
    @DisplayName("白名单：停用工具是全局生效的，任何角色都拿不到")
    void disabledToolIsUnavailableToEveryone() throws Exception {
        String token = loginAndGetToken(adminUsername);

        try {
            mockMvc.perform(put("/api/admin/tools/search_news/status")
                            .header("Authorization", bearer(token))
                            .param("enabled", "false"))
                    .andExpect(status().isOk());

            assertThat(toolRegistry.toolNamesForRole(CurrentUser.ROLE_USER))
                    .doesNotContain("search_news");
            assertThat(toolRegistry.toolNamesForRole(CurrentUser.ROLE_ADMIN))
                    .doesNotContain("search_news");
        } finally {
            mockMvc.perform(put("/api/admin/tools/search_news/status")
                            .header("Authorization", bearer(token))
                            .param("enabled", "true"))
                    .andExpect(status().isOk());
        }

        assertThat(toolRegistry.toolNamesForRole(CurrentUser.ROLE_USER)).contains("search_news");
    }

    @Test
    @DisplayName("白名单：接口对不存在的工具/角色返回 404")
    void unknownToolOrRoleReturns404() throws Exception {
        String token = loginAndGetToken(adminUsername);

        mockMvc.perform(put("/api/admin/tools/no_such_tool/status")
                        .header("Authorization", bearer(token))
                        .param("enabled", "false"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/admin/tools/roles/NO_SUCH_ROLE")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolNames\":[]}"))
                .andExpect(status().isNotFound());
    }

    private static String toJsonArray(Set<String> names) {
        return names.stream().map(n -> "\"" + n + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
