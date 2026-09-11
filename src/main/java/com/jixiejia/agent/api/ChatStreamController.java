package com.jixiejia.agent.api;

import com.jixiejia.agent.api.dto.ChatRequest;
import com.jixiejia.agent.auth.AuthContext;
import com.jixiejia.agent.auth.CurrentUser;
import com.jixiejia.agent.chat.ChatService;
import com.jixiejia.agent.router.RoutingDecision;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流式对话接口（SSE）。
 *
 * <p>事件顺序：
 * <pre>
 *   route   ← 路由完成即推，含意图与命中的 Agent（很快，关键词命中时根本不调模型）
 *   answer  ← 最终回答
 *   done    ← 结束
 * </pre>
 *
 * <p>为什么要在 Agent 执行<b>之前</b>先推 route：一次 Agent 调用要跑模型 + 工具循环，
 * 可能几十秒。用户先看到"识别为设备查询，交给 EquipmentAgent"至少知道系统在动，
 * 而不是盯着空白页面猜是不是卡了。
 *
 * <p>关于"逐字流式"：当前推的是事件粒度的流（路由 → 回答），不是 token 粒度的逐字输出。
 * 后者需要把 LangGraph4j 的 StreamingChatGenerator 接进 ReAct 图内部，
 * 属于体验优化，不影响链路正确性，留待后续。
 */
@Slf4j
@Tag(name = "对话-流式", description = "SSE 流式对话，需登录")
@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {

    /** SSE 连接超时：给足 5 分钟，Agent 慢的时候不至于被服务端先掐断 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final ChatService chatService;
    private final ThreadPoolTaskExecutor chatExecutor;

    public ChatStreamController(ChatService chatService,
                                @Qualifier("chatExecutor") ThreadPoolTaskExecutor chatExecutor) {
        this.chatService = chatService;
        this.chatExecutor = chatExecutor;
    }

    @Operation(summary = "流式对话", description = "以 SSE 推送 route / answer / done 事件")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        // 必须在请求线程上取：ThreadLocal 不会跟着任务传到执行线程去
        CurrentUser user = AuthContext.get();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        if (request.message() == null || request.message().isBlank()) {
            completeWithError(emitter, "消息内容不能为空");
            return emitter;
        }
        if (user == null) {
            completeWithError(emitter, "未登录");
            return emitter;
        }

        String roleKey = user.isAdmin() ? CurrentUser.ROLE_ADMIN : CurrentUser.ROLE_USER;
        String message = request.message().trim();

        chatExecutor.execute(() -> {
            try {
                ChatService.ChatResult result = chatService.chat(
                        user.userId(), null, roleKey, request.conversationId(), message,
                        decision -> sendRoute(emitter, decision));

                send(emitter, "answer", Map.of(
                        "conversationId", result.conversationId(),
                        "answer", result.answer()));
                send(emitter, "done", Map.of("conversationId", result.conversationId()));
                emitter.complete();

            } catch (Exception e) {
                log.error("流式对话执行失败", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /** 推送路由结果。失败只记日志——前端断开时这里会抛异常，不该影响后续生成。 */
    private void sendRoute(SseEmitter emitter, RoutingDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", decision.intent() == null ? null : decision.intent().name());
        payload.put("confidence", decision.confidence());
        payload.put("agentKey", decision.agentKey());
        payload.put("stage", decision.stage().code());
        payload.put("shortCircuit", decision.isShortCircuited());
        send(emitter, "route", payload);
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            log.debug("SSE 事件 {} 推送失败（前端可能已断开）：{}", event, e.toString());
        }
    }

    private void completeWithError(SseEmitter emitter, String message) {
        send(emitter, "error", Map.of("message", message));
        emitter.complete();
    }
}
