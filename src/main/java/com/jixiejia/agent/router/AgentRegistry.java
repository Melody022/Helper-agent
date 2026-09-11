package com.jixiejia.agent.router;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.persistence.entity.ai.AiAgent;
import com.jixiejia.agent.persistence.mapper.ai.AiAgentMapper;
import com.jixiejia.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 第 ⑥ 步：Agent 注册表与匹配。
 *
 * <p>匹配规则（不涉及模型，纯确定性）：
 * <ol>
 *   <li>取意图所需的能力集合；</li>
 *   <li>在启用的非兜底 Agent 里，找能力交集最大的那个；</li>
 *   <li>交集相同时取 priority 大的（ai_agent.priority，数值越大越优先）；</li>
 *   <li>一个都匹配不上时，才用 is_fallback=1 的兜底 Agent。</li>
 * </ol>
 *
 * <p>匹配出的 Agent 只能拿到"它自己声明具备的能力"下的工具，再与角色白名单求交集——
 * 两道限制缺一不可：前者防止 EquipmentAgent 越界去查出租，
 * 后者防止普通用户拿到管理类工具。
 *
 * <p>每次调用都现查 ai_agent，不缓存：这样管理台改了优先级或停用某个 Agent 立刻生效，
 * 而表里只有几行，代价可以忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRegistry {

    /** 匹配结果。 */
    public record AgentMatch(String agentKey, String agentName, Set<String> capabilities,
                             Set<String> toolNames, boolean fallback) {
    }

    private final AiAgentMapper agentMapper;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper json = JsonMapper.builder().build();

    /** 匹配打分的中间态：Agent 与它和能力需求的重合个数。 */
    private record Scored(AiAgent agent, int overlap) {
    }

    /** 匹配处理该意图的 Agent。 */
    public Optional<AgentMatch> match(Intent intent, String roleKey) {
        Set<String> roleTools = toolRegistry.toolNamesForRole(roleKey);
        List<AiAgent> agents = enabledAgents();

        Optional<AiAgent> best = agents.stream()
                .filter(a -> !isFallback(a))
                .map(a -> new Scored(a, overlap(parseCapabilities(a), intent.capabilities())))
                .filter(s -> s.overlap() > 0)
                .max(Comparator.comparingInt(Scored::overlap)
                        .thenComparingInt(s -> s.agent().getPriority() == null ? 0 : s.agent().getPriority()))
                .map(Scored::agent);

        return best.map(a -> toMatch(a, roleTools))
                .or(() -> fallback(roleKey));
    }

    /** 取兜底 Agent（GeneralAgent）。 */
    public Optional<AgentMatch> fallback(String roleKey) {
        Set<String> roleTools = toolRegistry.toolNamesForRole(roleKey);
        return enabledAgents().stream()
                .filter(AgentRegistry::isFallback)
                .max(Comparator.comparingInt(a -> a.getPriority() == null ? 0 : a.getPriority()))
                .map(a -> toMatch(a, roleTools));
    }

    /**
     * 按 agentKey 取 Agent，用于会话粘性复用。
     * 该 Agent 若已被停用或不存在，返回 empty——此时调用方应回退到按意图重新匹配，
     * 而不是硬把消息塞给一个已经下线的 Agent。
     */
    public Optional<AgentMatch> byKey(String agentKey, String roleKey) {
        if (agentKey == null || agentKey.isBlank()) {
            return Optional.empty();
        }
        Set<String> roleTools = toolRegistry.toolNamesForRole(roleKey);
        return enabledAgents().stream()
                .filter(a -> agentKey.equals(a.getAgentKey()))
                .findFirst()
                .map(a -> toMatch(a, roleTools));
    }

    private AgentMatch toMatch(AiAgent agent, Set<String> roleTools) {
        Set<String> capabilities = parseCapabilities(agent);

        // 该 Agent 能力范围内的全部工具，再与角色白名单求交集
        Set<String> allowed = new LinkedHashSet<>(toolRegistry.toolNamesForCapabilities(capabilities));
        allowed.retainAll(roleTools);

        return new AgentMatch(agent.getAgentKey(), agent.getAgentName(),
                capabilities, allowed, isFallback(agent));
    }

    /** 启用的 Agent。 */
    public List<AiAgent> enabledAgents() {
        return agentMapper.selectList(
                Wrappers.<AiAgent>lambdaQuery().eq(AiAgent::getStatus, "0"));
    }

    private static boolean isFallback(AiAgent agent) {
        return agent.getIsFallback() != null && agent.getIsFallback() == 1;
    }

    private static int overlap(Set<String> agentCaps, Set<String> requiredCaps) {
        if (agentCaps.isEmpty() || requiredCaps.isEmpty()) {
            return 0;
        }
        Set<String> copy = new LinkedHashSet<>(agentCaps);
        copy.retainAll(requiredCaps);
        return copy.size();
    }

    /** ai_agent.capabilities 是 JSON 数组字符串，解析失败按"无能力"处理。 */
    private Set<String> parseCapabilities(AiAgent agent) {
        String raw = agent.getCapabilities();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        try {
            var node = json.readTree(raw);
            if (!node.isArray()) {
                return Set.of();
            }
            Set<String> result = new LinkedHashSet<>();
            node.forEach(n -> {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) {
                    result.add(v);
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("Agent {} 的 capabilities 解析失败：{}", agent.getAgentKey(), raw);
            return Set.of();
        }
    }
}
