package com.jixiejia.agent.router;

import com.jixiejia.agent.classify.ClassifyLayer;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.classify.IntentResult;
import com.jixiejia.agent.persistence.entity.ai.AiAuditLog;
import com.jixiejia.agent.persistence.mapper.ai.AiAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 第 ⑦ 步（收尾）：把本轮路由的判定依据落进 ai_audit_log。
 *
 * <p>这张表回答的是"这条消息为什么被这么处理"——命中了哪一步、意图怎么来的、
 * 第几层定的案、交给了哪个 Agent、用了哪些工具。没有它，线上出现"答非所问"
 * 时只能靠猜；有了它，可以直接看出是分类错了、匹配错了，还是工具返回空。
 *
 * <p>审计写入失败不影响对话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditService {

    private final AiAuditLogMapper auditLogMapper;
    private final ObjectMapper json = JsonMapper.builder().build();

    /** 记录一次成功的路由（按意图结果的各字段展开）。 */
    public void record(RoutingRequest request, RouteStage stage, IntentResult result,
                       String agentKey, Set<String> toolNames, String reason, int latencyMs) {
        if (result == null) {
            record(request, stage, null, 0.0, null, agentKey, toolNames, reason, latencyMs);
            return;
        }
        record(request, stage, result.intent(), result.confidence(), result.layer(),
                agentKey, toolNames, reason, latencyMs);
    }

    /**
     * 记录一次成功的路由。
     *
     * <p>刻意收显式参数而不是只收 {@link IntentResult}：短路路径（系统命令、身份拦截）
     * 手里只有 {@link RoutingDecision}，拿不出 IntentResult，收显式参数两边都能用。
     */
    public void record(RoutingRequest request, RouteStage stage, Intent intent, double confidence,
                       ClassifyLayer layer, String agentKey, Set<String> toolNames,
                       String reason, int latencyMs) {
        AiAuditLog row = new AiAuditLog();
        row.setConversationId(request.conversationId());
        row.setUserId(request.aiUserId());
        row.setRouteStage(stage.code());
        row.setIntent(intent == null ? null : intent.name());
        row.setConfidence(BigDecimal.valueOf(confidence));
        row.setClassifyLayer(layer == null ? null : layer.code());
        row.setAgentKey(agentKey);
        row.setToolNames(toolNames == null || toolNames.isEmpty() ? null : String.join(",", toolNames));
        row.setDecision(toJson(reason));
        row.setLatencyMs(latencyMs);
        row.setSuccess(1);
        write(row);
    }

    /** 记录一次路由异常。 */
    public void recordError(RoutingRequest request, String errorMsg, int latencyMs) {
        AiAuditLog row = new AiAuditLog();
        row.setConversationId(request == null ? null : request.conversationId());
        row.setUserId(request == null ? null : request.aiUserId());
        row.setRouteStage(RouteStage.ERROR.code());
        row.setLatencyMs(latencyMs);
        row.setSuccess(0);
        row.setErrorMsg(errorMsg);
        write(row);
    }

    private void write(AiAuditLog row) {
        try {
            auditLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("审计日志写入失败（不影响对话）：{}", e.toString());
        }
    }

    private String toJson(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        try {
            return json.writeValueAsString(java.util.Map.of("reason", reason));
        } catch (Exception e) {
            return null;
        }
    }
}
