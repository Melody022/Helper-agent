package com.jixiejia.agent.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 登录与角色校验拦截器。
 *
 * <p>策略是<b>默认拒绝</b>：挂在 {@code /api/**} 上，除了显式列出的公开路径，
 * 其余一律要求登录。新增接口时忘记加 {@link RequireRole} 的后果只是"普通用户也能访问"，
 * 而不是"任何人都能访问"——白名单式放行比黑名单式拦截安全得多。
 *
 * <p>令牌从 {@code Authorization: Bearer <token>} 读。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 无需登录即可访问的路径 */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/health");

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenStore tokenStore;
    private final ObjectMapper json = JsonMapper.builder().build();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String path = request.getRequestURI();
        boolean isPublic = PUBLIC_PATHS.contains(path);

        // 预检请求不带 Authorization，放行交给 CORS 处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Optional<CurrentUser> user = tokenStore.resolve(extractToken(request));
        user.ifPresent(AuthContext::set);

        if (user.isEmpty()) {
            if (isPublic) {
                return true;
            }
            writeError(response, HttpStatus.UNAUTHORIZED, "未登录或登录已过期，请重新登录");
            return false;
        }

        if (isPublic) {
            return true;
        }

        // 已登录，再看是否满足该接口的角色要求
        Set<String> required = requiredRoles(handler);
        if (!required.isEmpty() && required.stream().noneMatch(user.get()::hasRole)) {
            log.info("越权访问被拒：用户 {} 访问 {}，需要角色 {}",
                    user.get().username(), path, required);
            writeError(response, HttpStatus.FORBIDDEN, "没有权限访问该功能");
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理：线程池复用线程，漏清会让下一个请求读到上一个用户的身份
        AuthContext.clear();
    }

    /** 方法上的 @RequireRole 优先于类上的。 */
    private static Set<String> requiredRoles(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return Set.of();
        }
        RequireRole annotation = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        return annotation == null ? Set.of() : Set.of(annotation.value());
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()).trim()
                : header.trim();
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message)
            throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of(
                "code", status.value(),
                "message", message)));
    }
}
