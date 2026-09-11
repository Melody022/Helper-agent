package com.jixiejia.agent.api;

import com.jixiejia.agent.api.dto.ChatRequest;
import com.jixiejia.agent.api.dto.ChatResponse;
import com.jixiejia.agent.auth.AuthContext;
import com.jixiejia.agent.auth.CurrentUser;
import com.jixiejia.agent.chat.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对话接口。
 *
 * <p>鉴权由 {@link com.jixiejia.agent.auth.AuthInterceptor} 统一处理：
 * 这个路径不在公开名单里，所以到达这里的请求一定已经登录。
 */
@Tag(name = "对话", description = "与 Agent 对话，需登录")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "发一条消息", description = "返回回答，以及本轮命中的意图与 Agent")
    @PostMapping
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "消息内容不能为空"));
        }

        // 能走到这里说明拦截器已经校验过登录态，CurrentUser 必然存在
        CurrentUser user = AuthContext.get();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "未登录"));
        }

        ChatService.ChatResult result = chatService.chat(
                user.userId(),
                null,                       // memberId：与平台会员的映射在 M7 发布流程里建立
                primaryRoleKey(user),
                request.conversationId(),
                request.message().trim());

        return ResponseEntity.ok(ChatResponse.of(result));
    }

    /** 取用户的"主角色"。同时是管理员和普通用户时按管理员算，权限取高的那个。 */
    private static String primaryRoleKey(CurrentUser user) {
        return user.isAdmin() ? CurrentUser.ROLE_ADMIN : CurrentUser.ROLE_USER;
    }
}
