package com.jixiejia.agent.api;

import com.jixiejia.agent.api.dto.ChangePasswordRequest;
import com.jixiejia.agent.api.dto.LoginRequest;
import com.jixiejia.agent.api.dto.LoginResponse;
import com.jixiejia.agent.api.dto.UserInfo;
import com.jixiejia.agent.auth.AuthContext;
import com.jixiejia.agent.auth.AuthService;
import com.jixiejia.agent.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 登录接口。
 *
 * <p>Agent 必须登录后才能使用，所以这里是所有对话能力的前置入口。
 * 登录成功返回令牌，后续请求带 {@code Authorization: Bearer <token>}。
 */
@Tag(name = "鉴权", description = "登录 / 注销 / 当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录", description = "账号密码登录，返回令牌")
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<String> token = authService.login(request.username(), request.password());
        if (token.isEmpty()) {
            // 不区分"账号不存在"和"密码错误"，避免账号枚举
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "账号或密码不正确"));
        }

        CurrentUser user = authService.resolve(token.get()).orElseThrow();
        return ResponseEntity.ok(new LoginResponse(token.get(), UserInfo.of(user)));
    }

    @Operation(summary = "注销", description = "让当前令牌立即失效")
    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // 令牌取出后直接作废；令牌本身无效时也返回成功，注销应当是幂等的
        String token = authorization == null ? null
                : authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
        authService.logout(token);
        return ResponseEntity.ok(Map.of("code", 200, "message", "已退出登录"));
    }

    @Operation(summary = "当前用户", description = "返回当前登录用户及其角色")
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        CurrentUser user = AuthContext.get();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "未登录"));
        }
        return ResponseEntity.ok(UserInfo.of(user));
    }

    @Operation(summary = "修改密码",
            description = "需要提供原密码。改完所有登录态会失效，需要重新登录")
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        CurrentUser user = AuthContext.get();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "未登录"));
        }

        // 统一返回这一句，不区分"原密码错"和"新密码不合规"，避免给爆破者反馈
        if (!authService.changePassword(user.userId(), request.oldPassword(), request.newPassword())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "原密码不正确，或新密码不符合要求（至少 6 位）"));
        }
        return ResponseEntity.ok(Map.of("code", 200,
                "message", "密码已修改。为保证安全，之前的登录状态已全部失效，请重新登录。"));
    }
}
