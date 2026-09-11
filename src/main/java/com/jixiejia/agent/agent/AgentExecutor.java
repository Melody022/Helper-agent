package com.jixiejia.agent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按 agentKey 找到对应 Agent 并执行。
 *
 * <p>这里刻意不缓存 Agent 实例的匹配结果，而是启动时把 Spring 容器里所有
 * {@link BizAgent} 收成一张 map——新增 Agent 只要写一个 @Component 类就自动生效，
 * 与工具注册表是同一套思路。
 */
@Slf4j
@Component
public class AgentExecutor {

    private final Map<String, BizAgent> agentsByKey;

    public AgentExecutor(List<BizAgent> agents) {
        Map<String, BizAgent> map = new LinkedHashMap<>();
        for (BizAgent agent : agents) {
            BizAgent previous = map.put(agent.agentKey(), agent);
            if (previous != null) {
                log.warn("Agent key 冲突：{} 被 {} 覆盖", agent.agentKey(), agent.getClass().getSimpleName());
            }
        }
        this.agentsByKey = Map.copyOf(map);
        log.info("Agent 装配完成：{}", map.keySet());
    }

    /** 按 agentKey 找 Agent。 */
    public Optional<BizAgent> find(String agentKey) {
        return agentKey == null ? Optional.empty() : Optional.ofNullable(agentsByKey.get(agentKey));
    }

    /** 全部已装配的 Agent key。 */
    public java.util.Set<String> agentKeys() {
        return agentsByKey.keySet();
    }

    /**
     * 执行一轮对话。
     *
     * <p>agentKey 在注册表里存在但代码里没有对应实现时（比如库里配了、代码删了），
     * 返回 empty 交给上层兜底，而不是抛异常。
     */
    public Optional<String> execute(String agentKey, String userText,
                                    List<Message> history, List<ToolCallback> tools) {
        return find(agentKey).map(agent -> agent.reply(userText, history, tools));
    }
}
