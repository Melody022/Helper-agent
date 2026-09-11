package com.jixiejia.agent.router;

import com.jixiejia.agent.classify.ClassifyLayer;
import com.jixiejia.agent.classify.Intent;

import java.util.Set;

/**
 * 路由链的产出：这一句话交给谁、能用哪些工具、是否需要短路回复。
 *
 * @param stage          链路终止于哪一步
 * @param intent         识别出的意图
 * @param confidence     意图置信度
 * @param classifyLayer  意图在哪一层定案
 * @param agentKey       命中的 Agent，短路时为 null
 * @param toolNames      该 Agent 在本角色下可用的工具
 * @param resolvedText   指代消解后的文本，未做消解时等于原文
 * @param reply          短路回复（转人工/投诉/系统命令）；非短路为 null
 * @param reason         判定依据，落审计
 */
public record RoutingDecision(
        RouteStage stage,
        Intent intent,
        double confidence,
        ClassifyLayer classifyLayer,
        String agentKey,
        Set<String> toolNames,
        String resolvedText,
        String reply,
        String reason
) {

    /** 短路决策：不经过 Agent，直接回固定话术。 */
    public static RoutingDecision shortCircuit(RouteStage stage, Intent intent, String reply, String reason) {
        return new RoutingDecision(stage, intent, 1.0, ClassifyLayer.KEYWORD,
                null, Set.of(), null, reply, reason);
    }

    public boolean isShortCircuited() {
        return reply != null;
    }

    /** 是否需要真正跑 Agent。 */
    public boolean needsAgent() {
        return !isShortCircuited() && agentKey != null;
    }
}
