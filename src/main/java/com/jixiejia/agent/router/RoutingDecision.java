package com.jixiejia.agent.router;

import com.jixiejia.agent.classify.ClassifyLayer;
import com.jixiejia.agent.classify.Intent;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

/**
 * 路由链的产出：这一句话交给谁、能用哪些工具、是否需要短路回复。
 *
 * <p>{@code Serializable} 是给路由状态图用的：整个对象要作为状态在图的节点之间传递，
 * 而 LangGraph4j 在克隆状态与接 checkpointer 时会序列化整个状态。
 * 成分全是枚举/基本类型/String/Set，实现这个接口不需要额外做任何事。
 *
 * @param stage          链路终止于哪一步
 * @param intent         识别出的意图
 * @param confidence     意图置信度
 * @param classifyLayer  意图在哪一层定案
 * @param agentKey       单域时命中的 Agent；跨域时为 null
 * @param agentKeys      跨域时命中的多个 Agent（并行执行），单域时为空
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
        Set<String> agentKeys,
        Set<String> toolNames,
        String resolvedText,
        String reply,
        String reason
) implements Serializable {

    /** 短路决策：不经过 Agent，直接回固定话术。 */
    public static RoutingDecision shortCircuit(RouteStage stage, Intent intent, String reply, String reason) {
        return new RoutingDecision(stage, intent, 1.0, ClassifyLayer.KEYWORD,
                null, Set.of(), Set.of(), null, reply, reason);
    }

    public boolean isShortCircuited() {
        return reply != null;
    }

    /** 是否需要真正跑 Agent。 */
    public boolean needsAgent() {
        return !isShortCircuited() && (agentKey != null || isComposite());
    }

    /** 是否走了跨域综合子图。 */
    public boolean isComposite() {
        return agentKeys != null && agentKeys.size() >= 2;
    }

    /** 本轮要执行的 Agent 列表：单域一个，跨域多个。 */
    public List<String> targetAgents() {
        if (isComposite()) {
            return List.copyOf(agentKeys);
        }
        return agentKey == null ? List.of() : List.of(agentKey);
    }
}
