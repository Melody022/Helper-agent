package com.jixiejia.agent.graph;

import com.jixiejia.agent.agent.AgentExecutor;
import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import com.jixiejia.agent.router.AgentRegistry;
import com.jixiejia.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 跨域综合子图：一句话命中多个领域时，各域 Agent 并行跑，最后汇总成一条回答。
 *
 * <p>图结构（静态，节点数 = 所有已装配 Agent 数）：
 * <pre>
 *        ┌─ n_EquipmentAgent ─┐
 *   START├─ n_RentalAgent    ─┼─→ synthesize ─→ END
 *        ├─ n_KnowledgeAgent ─┤
 *        └─ ...               ┘
 * </pre>
 *
 * <p><b>并行是隐式的</b>：LangGraph4j 没有"并行节点"的公开 API，也不支持
 * {@code addEdge(List, String)} 这种多源汇合写法。它的机制是——给同一个源节点挂多条出边，
 * 编译期自动生成一个合成并行节点（{@code __PARALLEL__(源id)}），
 * 用它把所有分支的结果合并后，再走那条唯一的出边。所以：<b>所有分支必须汇合到同一个后继</b>，
 * 否则 {@code compile()} 直接抛错；扇出的源也不能再用条件边。
 *
 * <p>为什么每个 Agent 都挂一个节点、而不是按本轮命中的域动态构图：
 * 图在启动时编译一次并复用。5 个 Agent 的非空子集有 31 种，动态构图要么每次重编译，
 * 要么缓存 31 张图。改成"固定挂满节点，每个节点先看自己在不在本轮名单里，不在就立刻返回空"
 * 只需一张图，空转节点的开销可以忽略。
 */
@Slf4j
@Component
public class CompositeGraph {

    /** 综合节点的名字 */
    private static final String SYNTHESIZE = "synthesize";

    /** 节点名前缀，避免与 LangGraph4j 的保留名冲突 */
    private static final String NODE_PREFIX = "domain_";

    /** 跨域结果 */
    public record CompositeResult(String answer, List<String> agentKeys, int succeeded) {
    }

    private static final String SYNTHESIS_SYSTEM = """
            你是「机械家」二手工程机械平台的智能助手。

            用户在一个问题里同时问了几个不同领域的事，平台各个领域的助手已经分别查到了资料。
            你的任务是把这些资料**合并成一条连贯、好读的回答**，直接回答用户的问题。

            必须遵守：
            1. 只使用下面提供的资料。**绝对不要**补充资料里没有的设备、价格、租金、规则条款。
            2. 某个领域没查到或查询失败时，如实说明那一部分暂时查不到，
               不要用常识或对其它平台的印象去补。
            3. 资料里的事实（价格、年份、小时数、地区、租金）原样保留，不要改写、不要换算、不要四舍五入。
            4. 按用户提问的顺序组织回答。用户先问什么就先答什么。
            5. 用口语，不要输出 JSON 或字段名，不要复述"资料显示"这类话。
            """;

    private final AgentExecutor agentExecutor;
    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;
    private final LlmClients clients;
    private final ModelCaller modelCaller;
    private final ThreadPoolTaskExecutor chatExecutor;

    /** 单轮跨域最多并行跑几个域 */
    private final int maxAgents;

    /** 综合这一步的超时：它要读完各域结果再写一段完整回答，比分类耗时得多 */
    private final long synthesisTimeoutMs;

    private volatile CompiledGraph<CompositeState> compiled;

    public CompositeGraph(AgentExecutor agentExecutor,
                          AgentRegistry agentRegistry,
                          ToolRegistry toolRegistry,
                          LlmClients clients,
                          ModelCaller modelCaller,
                          @Qualifier("chatExecutor") ThreadPoolTaskExecutor chatExecutor,
                          @Value("${routing.cross-domain.max-agents:3}") int maxAgents,
                          @Value("${routing.cross-domain.synthesis-timeout-ms:90000}") long synthesisTimeoutMs) {
        this.agentExecutor = agentExecutor;
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.clients = clients;
        this.modelCaller = modelCaller;
        this.chatExecutor = chatExecutor;
        this.maxAgents = maxAgents;
        this.synthesisTimeoutMs = synthesisTimeoutMs;
    }

    public int maxAgents() {
        return maxAgents;
    }

    @PostConstruct
    void build() {
        List<String> agents = new ArrayList<>(agentExecutor.agentKeys());
        if (agents.isEmpty()) {
            log.warn("没有装配任何 Agent，跨域综合子图不可用");
            return;
        }

        StateGraph<CompositeState> graph = new StateGraph<>(
                CompositeState.SCHEMA,
                new ObjectStreamStateSerializer<>(CompositeState::new));

        try {
            for (String agentKey : agents) {
                graph.addNode(nodeId(agentKey), domainNode(agentKey));
            }
            graph.addNode(SYNTHESIZE, AsyncNodeAction.node_async(this::synthesize));

            // 同一个源（START）挂多条出边 → 编译期自动扇出；
            // 所有分支再汇合到同一个 SYNTHESIZE → 编译期自动汇合
            for (String agentKey : agents) {
                graph.addEdge(GraphDefinition.START, nodeId(agentKey));
                graph.addEdge(nodeId(agentKey), SYNTHESIZE);
            }
            graph.addEdge(SYNTHESIZE, GraphDefinition.END);

            this.compiled = graph.compile();
            log.info("跨域综合子图已构建：{} 个领域节点 + 1 个综合节点", agents.size());

        } catch (Exception e) {
            // 构图失败不该阻断整个应用：跨域不可用时路由会退回单域
            log.error("跨域综合子图构建失败，跨域能力不可用", e);
        }
    }

    /** 子图是否可用。 */
    public boolean available() {
        return compiled != null;
    }

    /**
     * 跑一次跨域综合。
     *
     * @param agentKeys 本轮要跑的 Agent，调用方需保证 ≥2 个且在已装配范围内
     */
    public CompositeResult run(String message, String roleKey, List<String> agentKeys) {
        if (compiled == null) {
            return new CompositeResult(null, agentKeys, 0);
        }
        // 兜住上限，防止上游算错把五个域全塞进来
        List<String> targets = agentKeys.size() > maxAgents
                ? agentKeys.subList(0, maxAgents)
                : agentKeys;

        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(CompositeState.MESSAGE, message);
            input.put(CompositeState.ROLE, roleKey);
            input.put(CompositeState.AGENT_KEYS, List.copyOf(targets));

            CompositeState finalState = compiled.invoke(input).orElse(null);
            if (finalState == null) {
                return new CompositeResult(null, targets, 0);
            }
            return new CompositeResult(finalState.answer(), targets, finalState.results().size());

        } catch (Exception e) {
            log.error("跨域综合执行失败", e);
            return new CompositeResult(null, targets, 0);
        }
    }

    /**
     * 一个领域节点。本轮没选中它时立刻返回空增量，不产生任何开销。
     *
     * <p>并行分支内部必须自己吞掉异常：LangGraph4j 的并行节点用 allOfFailFast 等所有分支，
     * 任何一条分支抛异常都会让整轮汇总失败——一个域查不到不该拖垮其它域。
     */
    private AsyncNodeAction<CompositeState> domainNode(String agentKey) {
        return state -> {
            if (!state.agentKeys().contains(agentKey)) {
                return CompletableFuture.completedFuture(Map.of());
            }
            return CompletableFuture.supplyAsync(() -> runOneDomain(agentKey, state), chatExecutor);
        };
    }

    private Map<String, Object> runOneDomain(String agentKey, CompositeState state) {
        long startedAt = System.currentTimeMillis();
        try {
            AgentRegistry.AgentMatch match = agentRegistry.byKey(agentKey, state.role()).orElse(null);
            if (match == null) {
                log.warn("跨域分支 {} 在本角色下不可用，跳过", agentKey);
                return Map.of();
            }

            ToolCallback[] tools = toolRegistry.callbacksFor(match.toolNames());
            String answer = agentExecutor
                    .execute(agentKey, state.message(), List.of(), List.of(tools))
                    .orElse("");

            if (answer.isBlank()) {
                return Map.of();
            }

            log.debug("跨域分支 {} 完成，耗时 {} ms", agentKey, System.currentTimeMillis() - startedAt);
            return Map.of(CompositeState.RESULTS, List.of(formatSection(match.agentName(), answer)));

        } catch (Exception e) {
            log.warn("跨域分支 {} 执行失败，其余分支继续", agentKey, e);
            return Map.of(CompositeState.RESULTS,
                    List.of(formatSection(agentKey, "这部分暂时查询失败，请稍后单独问一次。")));
        }
    }

    /** 末端综合节点：把各域结果合并成一条回答。 */
    private Map<String, Object> synthesize(CompositeState state) {
        List<String> results = state.results();
        if (results.isEmpty()) {
            return Map.of(CompositeState.ANSWER,
                    "抱歉，这几个问题我都没能查到结果，换个说法再问一次，或者回复\"转人工\"联系客服。");
        }

        String user = "用户的问题：" + state.message()
                + "\n\n各领域助手查到的资料：\n" + String.join("\n\n", results);

        String answer = modelCaller.call(clients.main(), SYNTHESIS_SYSTEM, user, synthesisTimeoutMs);

        if (answer == null || answer.isBlank()) {
            // 综合这一步失败时，退回把各域结果原样拼接——信息是全的，只是不够顺
            log.warn("跨域综合调用模型失败，退回拼接各域原始结果");
            return Map.of(CompositeState.ANSWER, String.join("\n\n", results));
        }
        return Map.of(CompositeState.ANSWER, answer);
    }

    private static String formatSection(String title, String content) {
        return "【" + title + "】\n" + content;
    }

    private static String nodeId(String agentKey) {
        return NODE_PREFIX + agentKey;
    }
}
