package com.jixiejia.agent.router;

import com.jixiejia.agent.agent.AgentContext;
import com.jixiejia.agent.agent.AgentExecutor;
import com.jixiejia.agent.classify.ClassifyLayer;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.classify.IntentClassifier;
import com.jixiejia.agent.classify.IntentResult;
import com.jixiejia.agent.classify.KeywordWeightClassifier;
import com.jixiejia.agent.graph.CompositeGraph;
import com.jixiejia.agent.rag.KnowledgeAnswerService;
import com.jixiejia.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 路由链的状态图版本：把原来 {@code MsgRouter.doRoute()} 里那串 if/else 提前返回，
 * 改写成"节点 + 条件边"。
 *
 * <pre>
 *  START → identity ─┬─(拦下)─────────────────────────────→ END
 *                    └─→ command ─┬─(命中)─────────────────→ END
 *                                 └─→ scan ─┬─(跨域)───────→ END
 *                                           ├─(关键词定案)→ known → END
 *                                           ├─(粘性追问)───→ END
 *                                           └─→ resolve → classify ─┬─(短路/跨域)→ END
 *                                                                   └─→ match → END
 * </pre>
 *
 * <p><b>为什么要做成图：</b>原来"这一步之后去哪"散在 12 个 {@code return} 里，
 * 读代码时得在脑子里把控制流拼出来；现在它就是图上的一条边，一眼能看见。
 * 每条条件边都只读状态、不做业务——业务仍然在 {@code MsgRouter} 的步骤方法里，
 * <b>图和原来的方法链共用同一份实现</b>，不存在"逻辑抄了两遍慢慢漂移"的问题。
 *
 * <p><b>"是否已定案"用 {@code stage} 之外的东西判断：</b>终结节点会把整个
 * {@link RoutingDecision} 追加进 {@code DECISIONS} 通道，所以条件边问一句
 * {@link RoutingState#decided()} 就够了——不用在每条边上重写一遍业务条件，
 * 少一处重复就少一处漏判。
 *
 * <p><b>步数上限：</b>{@link #RECURSION_LIMIT} 显式传给 {@code CompileConfig}。
 * 库默认 25，对一条 7 步的链来说太松，等于没设。这是顺手补上的一个缺口：
 * 之前 {@code graph.invoke(map)} 从不传 {@code RunnableConfig}，
 * 实际依赖的是框架默认值，而不是自己的预算控制。
 */
@Slf4j
@Component
public class RoutingGraph {

    // 节点名
    private static final String N_IDENTITY = "identity";
    private static final String N_COMMAND = "command";
    private static final String N_SCAN = "scan";
    private static final String N_KNOWN = "known";
    private static final String N_RESOLVE = "resolve";
    private static final String N_CLASSIFY = "classify";
    private static final String N_MATCH = "match";
    private static final String N_DISPATCH = "dispatch";

    // 条件边标签
    private static final String L_CONTINUE = "continue";
    private static final String L_KNOWN = "known";
    private static final String L_RESOLVE = "resolve";
    private static final String L_MATCH = "match";

    // 执行节点名与去向标签。三条执行路本来就是三个分支，正好用条件边表达
    private static final String N_ANSWER_KNOWLEDGE = "answerKnowledge";
    private static final String N_ANSWER_COMPOSITE = "answerComposite";
    private static final String N_ANSWER_AGENT = "answerAgent";

    private static final String L_END = "end";
    private static final String L_KNOWLEDGE = "knowledge";
    private static final String L_COMPOSITE = "composite";
    private static final String L_AGENT = "agent";

    /** 短路时给 ChatResult 用的执行者名（没有真跑 Agent） */
    private static final String NO_EXECUTOR = "";

    /** 知识线的执行者名，审计与前端都靠它区分"这条走了检索+证据闸" */
    private static final String KNOWLEDGE_EXECUTOR = "Knowledge(检索+证据闸)";

    /** 路由选中的 Agent 在代码里没有对应实现（正常运维下不该出现） */
    private static final String AGENT_MISSING_REPLY =
            "抱歉，这个功能暂时不可用，请稍后再试或回复\"转人工\"联系客服。";

    /** 跨域各域都没查到时的话术 */
    private static final String COMPOSITE_FAILED_REPLY =
            "抱歉，你这几个问题我都没能查到，可以分开一个个问，或者回复\"转人工\"联系客服。";

    /** SSE 回调在 RunnableConfig.metadata 里的键 */
    private static final String META_ON_ROUTED = "onRouted";

    /** 指代消解与喂给 Agent 的历史都取最近几轮，与 MsgRouter 保持一致 */
    private static final int HISTORY_ROUNDS = 5;

    /** 取值与 RoutingState 的常量同源，避免字面量写两处慢慢漂移 */
    private static final String PATH_KNOWN = RoutingState.PATH_KNOWN;

    /** 指代消解取最近几轮对话，与 MsgRouter 保持一致 */
    private static final int REFERENCE_ROUNDS = 5;

    /**
     * 单次路由最多走多少个节点。
     *
     * <p>11 个节点（identity → command → scan → resolve → classify → match → dispatch →
     * answerXxx → END），最长路径 9 步，留一倍余量取 20。
     * 库默认 25 太松，起不到"防打转"的作用——这个上限是<b>自己的预算控制</b>，
     * 不是靠框架兜底。
     */
    private static final int RECURSION_LIMIT = 20;

    private final MsgRouter steps;
    private final CommandHandler commandHandler;
    private final StickySessionStore stickySessionStore;
    private final ConversationMemory conversationMemory;
    private final ReferenceResolver referenceResolver;
    private final KeywordWeightClassifier keywordClassifier;
    private final IntentClassifier intentClassifier;
    private final AgentRegistry agentRegistry;

    // 下面这几个是"执行"那一段要用的。执行进图之后，这三条路的依赖也跟着进来了——
    // 这是"骨架全入图"的代价：图不再只是"判定"，它真的把活干完了。
    private final KnowledgeAnswerService knowledgeAnswerService;
    private final CompositeGraph compositeGraph;
    private final AgentExecutor agentExecutor;
    private final ToolRegistry toolRegistry;

    private volatile CompiledGraph<RoutingState> compiled;

    public RoutingGraph(MsgRouter steps,
                        CommandHandler commandHandler,
                        StickySessionStore stickySessionStore,
                        ConversationMemory conversationMemory,
                        ReferenceResolver referenceResolver,
                        KeywordWeightClassifier keywordClassifier,
                        IntentClassifier intentClassifier,
                        AgentRegistry agentRegistry,
                        KnowledgeAnswerService knowledgeAnswerService,
                        CompositeGraph compositeGraph,
                        AgentExecutor agentExecutor,
                        ToolRegistry toolRegistry) {
        this.steps = steps;
        this.commandHandler = commandHandler;
        this.stickySessionStore = stickySessionStore;
        this.conversationMemory = conversationMemory;
        this.referenceResolver = referenceResolver;
        this.keywordClassifier = keywordClassifier;
        this.intentClassifier = intentClassifier;
        this.agentRegistry = agentRegistry;
        this.knowledgeAnswerService = knowledgeAnswerService;
        this.compositeGraph = compositeGraph;
        this.agentExecutor = agentExecutor;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    void build() {
        StateGraph<RoutingState> graph = new StateGraph<>(
                RoutingState.SCHEMA,
                new ObjectStreamStateSerializer<>(RoutingState::new));

        try {
            graph.addNode(N_IDENTITY, AsyncNodeAction.node_async(this::identityNode));
            graph.addNode(N_COMMAND, AsyncNodeAction.node_async(this::commandNode));
            graph.addNode(N_SCAN, AsyncNodeAction.node_async(this::scanNode));
            graph.addNode(N_KNOWN, AsyncNodeAction.node_async(this::knownNode));
            graph.addNode(N_RESOLVE, AsyncNodeAction.node_async(this::resolveNode));
            graph.addNode(N_CLASSIFY, AsyncNodeAction.node_async(this::classifyNode));
            graph.addNode(N_MATCH, AsyncNodeAction.node_async(this::matchNode));

            // 定案后的汇聚点：一个不做业务的"分派"节点，只把三条执行路分开。
            // 它同时是 SSE 推送的时机——路由完成、执行还没开始，前端能立刻看到反馈。
            graph.addNode(N_DISPATCH, AsyncNodeActionWithConfig.node_async(this::dispatchNode));
            graph.addNode(N_ANSWER_KNOWLEDGE, AsyncNodeAction.node_async(this::answerKnowledgeNode));
            graph.addNode(N_ANSWER_COMPOSITE, AsyncNodeAction.node_async(this::answerCompositeNode));
            graph.addNode(N_ANSWER_AGENT, AsyncNodeAction.node_async(this::answerAgentNode));

            graph.addEdge(GraphDefinition.START, N_IDENTITY);

            // 前两步：拦下就去分派，否则往下走
            graph.addConditionalEdges(N_IDENTITY, AsyncEdgeAction.edge_async(this::afterIdentity),
                    EdgeMappings.builder().to(N_COMMAND, L_CONTINUE).to(N_DISPATCH, L_END).build());
            graph.addConditionalEdges(N_COMMAND, AsyncEdgeAction.edge_async(this::afterCommand),
                    EdgeMappings.builder().to(N_SCAN, L_CONTINUE).to(N_DISPATCH, L_END).build());

            // 扫描之后有四个去向，其中两个直接定案
            graph.addConditionalEdges(N_SCAN, AsyncEdgeAction.edge_async(this::afterScan),
                    EdgeMappings.builder()
                            .to(N_KNOWN, L_KNOWN)
                            .to(N_RESOLVE, L_RESOLVE)
                            .to(N_DISPATCH, L_END).build());

            // 关键词已定案的支线：routeByKnownIntent 内部自己决定沿用粘性还是切换 Agent
            graph.addEdge(N_KNOWN, N_DISPATCH);

            graph.addEdge(N_RESOLVE, N_CLASSIFY);
            graph.addConditionalEdges(N_CLASSIFY, AsyncEdgeAction.edge_async(this::afterClassify),
                    EdgeMappings.builder().to(N_MATCH, L_MATCH).to(N_DISPATCH, L_END).build());
            graph.addEdge(N_MATCH, N_DISPATCH);

            // 分派：短路直接结束；否则按"资料不足不许开口 / 跨域并行 / 单 Agent"三条路走
            graph.addConditionalEdges(N_DISPATCH, AsyncEdgeAction.edge_async(this::afterDecision),
                    EdgeMappings.builder()
                            .to(N_ANSWER_KNOWLEDGE, L_KNOWLEDGE)
                            .to(N_ANSWER_COMPOSITE, L_COMPOSITE)
                            .to(N_ANSWER_AGENT, L_AGENT)
                            .toEND(L_END)
                            .build());
            graph.addEdge(N_ANSWER_KNOWLEDGE, GraphDefinition.END);
            graph.addEdge(N_ANSWER_COMPOSITE, GraphDefinition.END);
            graph.addEdge(N_ANSWER_AGENT, GraphDefinition.END);

            this.compiled = graph.compile(
                    CompileConfig.builder().recursionLimit(RECURSION_LIMIT).build());
            log.info("路由状态图已构建：11 个节点、5 条条件边，步数上限 {}", RECURSION_LIMIT);

        } catch (Exception e) {
            // 构图失败不该阻断应用：路由不可用时整个助手都用不了，但至少要把原因说清楚
            log.error("路由状态图构建失败", e);
        }
    }

    /** 图是否可用。 */
    public boolean available() {
        return compiled != null;
    }

    /**
     * <b>只判定、不执行</b>。签名与 {@link MsgRouter#route} 完全一致，专门给等价性对照测试用
     * （旧实现也不执行，口径得对齐）。
     */
    public RoutingDecision route(RoutingRequest request) {
        long start = System.currentTimeMillis();
        if (compiled == null) {
            return steps.errorDecision(request,
                    new IllegalStateException("路由状态图不可用（构图失败，见启动日志）"), start);
        }
        try {
            RoutingState finalState = compiled
                    .invoke(inputOf(request, start, false), configOf(null))
                    .orElse(null);
            if (finalState == null || finalState.decision() == null) {
                throw new IllegalStateException("图没有产出路由决策");
            }
            return finalState.decision();

        } catch (Exception e) {
            return steps.errorDecision(request, e, start);
        }
    }

    /**
     * <b>完整跑一轮</b>：判定 + 审计 + 执行，返回回答。
     *
     * @param onRouted 路由定案、执行开始前的回调（SSE 推给前端用），可为 null
     */
    public RoutingResult run(RoutingRequest request, Consumer<RoutingDecision> onRouted) {
        long start = System.currentTimeMillis();
        if (compiled == null) {
            return RoutingResult.of(steps.errorDecision(request,
                    new IllegalStateException("路由状态图不可用（构图失败，见启动日志）"), start));
        }
        try {
            RoutingState finalState = compiled
                    .invoke(inputOf(request, start, true), configOf(onRouted))
                    .orElse(null);
            if (finalState == null || finalState.decision() == null) {
                throw new IllegalStateException("图没有产出路由决策");
            }
            return new RoutingResult(finalState.decision(), finalState.answer(),
                    finalState.executedAgentKey(), finalState.sources());

        } catch (Exception e) {
            return RoutingResult.of(steps.errorDecision(request, e, start));
        }
    }

    private static Map<String, Object> inputOf(RoutingRequest request, long start, boolean execute) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(RoutingState.CONVERSATION_ID, request.conversationId());
        input.put(RoutingState.AI_USER_ID, request.aiUserId() == null ? 0L : request.aiUserId());
        input.put(RoutingState.MEMBER_ID, request.memberId() == null ? 0L : request.memberId());
        input.put(RoutingState.ROLE_KEY, request.effectiveRoleKey());
        input.put(RoutingState.MESSAGE, request.message() == null ? "" : request.message());
        input.put(RoutingState.STARTED_AT, start);
        input.put(RoutingState.EXECUTE, execute);
        return input;
    }

    /** SSE 回调走 config 的 metadata，不进 state——它是每次请求都不同的 lambda。 */
    private static RunnableConfig configOf(Consumer<RoutingDecision> onRouted) {
        RunnableConfig.Builder builder = RunnableConfig.builder();
        if (onRouted != null) {
            builder.putMetadata(META_ON_ROUTED, onRouted);
        }
        return builder.build();
    }

    /**
     * 一轮对话的完整产出：路由决策 + 回答。
     *
     * @param answer           给用户的回答（短路时就是决策里的 reply）
     * @param executedAgentKey 实际执行了谁；短路时为 null
     * @param sources          知识问答的溯源依据，其余为空
     */
    public record RoutingResult(RoutingDecision decision, String answer,
                                String executedAgentKey,
                                List<KnowledgeAnswerService.Source> sources) {

        /** 异常兜底：只有一句能看懂的话，没有决策可用。 */
        static RoutingResult of(RoutingDecision errorDecision) {
            return new RoutingResult(errorDecision, errorDecision.reply(), null, List.of());
        }
    }

    // ---------------- 节点 ----------------

    /** ① 身份检查：未登录／账号不存在／停用 → 定案；否则什么都不写，交给条件边往下走。 */
    private Map<String, Object> identityNode(RoutingState s) {
        Optional<RoutingDecision> blocked = steps.checkIdentity(requestOf(s));
        return blocked.map(d -> decisionDelta(s, d)).orElseGet(Map::of);
    }

    /** ② 系统命令：/reset 这类指令优先于一切语义理解。 */
    private Map<String, Object> commandNode(RoutingState s) {
        Optional<CommandHandler.CommandResult> command =
                commandHandler.handle(s.message().trim(), s.conversationId());
        if (command.isEmpty()) {
            return Map.of();
        }
        return decisionDelta(s, RoutingDecision.shortCircuit(RouteStage.COMMAND, Intent.UNKNOWN,
                command.get().reply(), "命中系统命令 " + command.get().command()));
    }

    /**
     * ③ 粘性 + 零成本关键词扫描。
     *
     * <p>这是最重的一个节点，因为它对应原方法里分支最多的那一段。它<b>只负责算和记录</b>——
     * 算出跨域命中 / 关键词定案 / 粘性可复用这三件事写进状态；
     * "接下来去哪"由 {@link #afterScan} 读状态决定。
     */
    private Map<String, Object> scanNode(RoutingState s) {
        String message = s.message().trim();
        String roleKey = s.roleKey();

        Optional<StickySessionStore.StickySession> sticky = stickySessionStore.find(s.conversationId());
        Optional<IntentResult> byKeyword = keywordClassifier.classify(message);

        // 跨域判定必须排在单域路由之前。否则它会走进 AgentRegistry 的"能力交集最多者胜"，
        // 被交集最多的那个 Agent 整条吃掉——用户问三件事，只答一件，且看不出错在哪。
        Optional<List<String>> crossAgents = steps.detectCrossDomain(message, roleKey);
        if (crossAgents.isPresent()) {
            RoutingDecision decision = new RoutingDecision(
                    RouteStage.COMPOSITE, Intent.CROSS_DOMAIN, 0.9, ClassifyLayer.KEYWORD,
                    null, new LinkedHashSet<>(crossAgents.get()), Set.of(),
                    message, null,
                    "命中 " + crossAgents.get().size() + " 个领域，走跨域综合子图：" + crossAgents.get());
            log.debug("跨域命中：{} -> {}", message, crossAgents.get());
            return decisionDelta(s, decision);
        }

        // STICKY / KEYWORD_RESULT 是"可空对象通道"：用 appender 装（空 List = 本轮没有），
        // 所以这里包一层 List.of——直接写对象的话会被当成"要 addAll 一个对象"而报错
        Map<String, Object> delta = new LinkedHashMap<>();
        sticky.ifPresent(st -> delta.put(RoutingState.STICKY, List.of(st)));
        byKeyword.ifPresent(kw -> delta.put(RoutingState.KEYWORD_RESULT, List.of(kw)));

        if (byKeyword.isPresent()) {
            delta.put(RoutingState.PATH, PATH_KNOWN);
            return delta;
        }

        // 关键词没命中：只有含指代词时才用粘性兜住，避免白跑一次模型。
        // 判据为什么只留"含指代词"一条，见 MsgRouter 的类注释（那里记着两次翻车）。
        if (referenceResolver.needsResolution(message) && sticky.isPresent()) {
            Optional<AgentRegistry.AgentMatch> reuse =
                    agentRegistry.byKey(sticky.get().agentKey(), roleKey);
            if (reuse.isPresent()) {
                Intent stickyIntent = Intent.parse(sticky.get().intent());
                RoutingDecision decision = new RoutingDecision(
                        RouteStage.STICKY, stickyIntent, 1.0, null,
                        reuse.get().agentKey(), Set.of(), reuse.get().toolNames(), message,
                        null, "关键词未命中且判定为追问，沿用会话粘性 Agent=" + reuse.get().agentKey());
                log.debug("粘性兜底：{} -> {}", message, reuse.get().agentKey());
                return decisionDelta(s, decision);
            }
            // 粘性里的 Agent 已被停用或删除，只能重新分类
            log.debug("粘性 Agent {} 已不可用，重新分类", sticky.get().agentKey());
        } else if (sticky.isPresent()) {
            log.debug("关键词未命中但不像追问，重新分类而不是沿用粘性：{}", message);
        }

        delta.put(RoutingState.PATH, RoutingState.PATH_FALLTHROUGH);
        return delta;
    }

    /**
     * 关键词已定案的支线：不叫模型，只判断该不该沿用粘性里的 Agent。
     *
     * <p>⚠️ 这里<b>不能</b>走 {@link #decisionDelta}：{@code routeByKnownIntent} 内部已经调过
     * {@code auditAndReturn} 了（它是从旧方法链原样复用过来的，每一步的返回点都自带审计）。
     * 再过一次 decisionDelta 就会写两条审计——一开始就是这么错的，
     * 被 {@code M4RouterTest.auditIsWritten}（断言恰好一条）逮住。
     */
    private Map<String, Object> knownNode(RoutingState s) {
        RoutingDecision decision = steps.routeByKnownIntent(
                requestOf(s), s.keywordResult(),
                Optional.ofNullable(s.sticky()), s.roleKey(), s.startedAt());
        return Map.of(RoutingState.DECISIONS, List.of(decision));
    }

    /** ④ 指代消解：只有真要叫模型时才做。 */
    private Map<String, Object> resolveNode(RoutingState s) {
        String recentTurns = conversationMemory.recentTurnsAsText(
                s.conversationId(), REFERENCE_ROUNDS);
        ReferenceResolver.Resolution resolution =
                referenceResolver.resolve(s.message().trim(), recentTurns);

        return RoutingState.delta(
                RoutingState.RECENT_TURNS, recentTurns,
                RoutingState.RESOLVED_TEXT, resolution.text(),
                RoutingState.REFERENCE_NOTE, resolution.note());
    }

    /** ⑤ 三层意图分类：整条链里模型唯一参与的地方。 */
    private Map<String, Object> classifyNode(RoutingState s) {
        IntentResult intent = intentClassifier.classify(s.resolvedText(), s.recentTurns());
        log.debug("意图分类：{} conf={} layer={}", intent.intent(), intent.confidence(), intent.layer());

        if (intent.intent().isShortCircuit()) {
            return decisionDelta(s, steps.shortCircuitFor(intent));
        }

        // 模型也可能判出跨域（关键词层漏掉的说法）。这条路径必须单独处理——
        // 直接丢进单 Agent 匹配，会被"能力交集最多者胜"整条吃掉。
        if (intent.intent() == Intent.CROSS_DOMAIN) {
            String roleKey = s.roleKey();
            List<String> agents = steps.resolveCrossAgents(intent, s.resolvedText(), roleKey);

            if (agents.size() >= 2) {
                RoutingDecision composite = new RoutingDecision(
                        RouteStage.COMPOSITE, Intent.CROSS_DOMAIN, intent.confidence(), intent.layer(),
                        null, new LinkedHashSet<>(agents), Set.of(), s.resolvedText(), null,
                        "模型判为跨域，涉及 " + agents.size() + " 个领域：" + agents);
                log.debug("模型判定跨域：{} -> {}", s.message(), agents);
                return decisionDelta(s, composite);
            }

            if (agents.size() == 1) {
                // 判成跨域但只有一个领域站得住，按单域走，别硬凑综合
                AgentRegistry.AgentMatch single =
                        steps.matchAgent(Intent.CROSS_DOMAIN, roleKey, agents.get(0));
                if (single == null) {
                    return decisionDelta(s, RoutingDecision.shortCircuit(
                            RouteStage.ERROR, Intent.CROSS_DOMAIN,
                            MsgRouter.NO_AGENT_REPLY, "无可用 Agent"));
                }
                RoutingDecision decision = new RoutingDecision(
                        RouteStage.EXECUTE, Intent.CROSS_DOMAIN, intent.confidence(), intent.layer(),
                        single.agentKey(), Set.of(), single.toolNames(), s.resolvedText(), null,
                        "模型判为跨域但只解析出 1 个领域，按单域处理：" + single.agentKey());
                return decisionDelta(s, decision);
            }
            log.debug("模型判为跨域但解析不出任何领域，退回兜底匹配");
        }

        // 还不够定案：把分类结果记进状态，交给 match 节点
        return RoutingState.delta(
                RoutingState.INTENT, intent.intent(),
                RoutingState.CONFIDENCE, intent.confidence(),
                RoutingState.CLASSIFY_LAYER, intent.layer(),
                RoutingState.INTENT_REASON, intent.reason());
    }

    /** ⑥ Agent 匹配：注册表 + 角色权限 + 优先级。 */
    private Map<String, Object> matchNode(RoutingState s) {
        Intent intent = s.intent() == null ? Intent.UNKNOWN : s.intent();
        AgentRegistry.AgentMatch match = steps.matchAgent(intent, s.roleKey(), null);
        if (match == null) {
            return decisionDelta(s, RoutingDecision.shortCircuit(RouteStage.ERROR, intent,
                    MsgRouter.NO_AGENT_REPLY, "Agent 注册表中没有可用 Agent"));
        }

        String note = s.referenceNote();
        String reason = (note != null ? note + "；" : "")
                + "意图=" + intent + "(" + s.confidence()
                + "/" + s.<ClassifyLayer>value(RoutingState.CLASSIFY_LAYER)
                        .orElse(ClassifyLayer.FALLBACK).code() + ")"
                + "；判定：" + (s.intentReason() == null ? "" : s.intentReason())
                + "；没有粘性可用，按意图匹配 Agent";

        RoutingDecision decision = new RoutingDecision(
                RouteStage.EXECUTE, intent, s.confidence(), s.classifyLayer(),
                match.agentKey(), Set.of(), match.toolNames(), s.resolvedText(), null, reason);
        return decisionDelta(s, decision);
    }

    // ---------------- 分派与执行 ----------------

    /**
     * 分派节点：不做业务，只把定案结果推给前端，然后交给条件边决定走哪条执行路。
     *
     * <p>用 {@code NodeActionWithConfig} 是为了拿到 {@link RunnableConfig}：
     * SSE 回调是<b>每次请求都不同</b>的东西（一个 lambda），塞进 state 会破坏状态的可序列化性。
     * 走 config 的 metadata 传进来，state 就还是干净的。
     *
     * <p>推送时机与原实现一致：**审计已经落库、执行还没开始**。执行可能很慢，
     * 用户得先看到"识别出什么意图、交给了谁"，而不是干等整段回答。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatchNode(RoutingState s, RunnableConfig config) {
        Object callback = config.metadata(META_ON_ROUTED).orElse(null);
        if (callback instanceof java.util.function.Consumer<?> consumer) {
            try {
                ((java.util.function.Consumer<RoutingDecision>) consumer).accept(s.decision());
            } catch (Exception e) {
                // 回调失败（比如前端已断开）不该影响这轮对话本身
                log.debug("路由回调执行失败，忽略：{}", e.toString());
            }
        }
        return Map.of();
    }

    /**
     * 知识线：检索 → 证据闸 → 生成，<b>不经过 ReAct Agent</b>。
     *
     * <p>这条道硬要求"资料不足就不许答"，而 Agent 里的模型有自主权——
     * 完全可能不去查资料、直接凭对同类平台的印象回答，那样证据闸就形同虚设。
     */
    private Map<String, Object> answerKnowledgeNode(RoutingState s) {
        RoutingDecision d = s.decision();
        KnowledgeAnswerService.Answer knowledge =
                knowledgeAnswerService.answer(d.resolvedText(), s.conversationId());
        if (!knowledge.answered()) {
            // 没答上来意味着这是个知识盲区，记进飞轮等人工补（由调用方负责记）
            log.debug("知识线未作答：{}", knowledge.note());
        }
        return RoutingState.delta(
                RoutingState.ANSWER, knowledge.text(),
                RoutingState.EXECUTED_AGENT_KEY, KNOWLEDGE_EXECUTOR,
                RoutingState.SOURCES, knowledge.sources());
    }

    /** 跨域：交给并行扇出子图，各域跑完汇总成一条回答。 */
    private Map<String, Object> answerCompositeNode(RoutingState s) {
        RoutingDecision d = s.decision();
        List<String> targets = List.copyOf(d.targetAgents());
        CompositeGraph.CompositeResult result =
                compositeGraph.run(d.resolvedText(), s.roleKey(), targets);

        String answer = (result.answer() == null || result.answer().isBlank())
                ? COMPOSITE_FAILED_REPLY
                : result.answer();
        if (COMPOSITE_FAILED_REPLY.equals(answer)) {
            log.warn("跨域综合未产出回答，目标域={}", result.agentKeys());
        }
        return RoutingState.delta(
                RoutingState.ANSWER, answer,
                RoutingState.EXECUTED_AGENT_KEY, String.join(",", targets));
    }

    /** 单 Agent：走它自己的 ReAct 图。 */
    private Map<String, Object> answerAgentNode(RoutingState s) {
        RoutingDecision d = s.decision();

        // 历史在这里现取而不是从 state 里传：Spring AI 的 Message 不可靠地可序列化，
        // 不该进状态。取的时刻在图跑完之后，所以本轮用户消息还不在里面——
        // 与原实现（路由前先取历史）一致。
        List<Message> history = conversationMemory.recentMessagesForModel(
                s.conversationId(), HISTORY_ROUNDS);

        ToolCallback[] tools = toolRegistry.callbacksFor(d.toolNames());
        String answer = agentExecutor
                .execute(d.agentKey(), new AgentContext(
                        s.conversationId(), s.aiUserId(), s.memberId(), s.roleKey(),
                        d.resolvedText(), history, List.of(tools)))
                .orElseGet(() -> {
                    log.warn("路由选中了 Agent {}，但代码中没有对应实现", d.agentKey());
                    return AGENT_MISSING_REPLY;
                });

        return RoutingState.delta(
                RoutingState.ANSWER, answer,
                RoutingState.EXECUTED_AGENT_KEY, d.agentKey());
    }

    // ---------------- 条件边：只读状态，不做业务 ----------------

    private String afterIdentity(RoutingState s) {
        return s.decided() ? L_END : L_CONTINUE;
    }

    private String afterCommand(RoutingState s) {
        return s.decided() ? L_END : L_CONTINUE;
    }

    private String afterScan(RoutingState s) {
        if (s.decided()) {
            return L_END;
        }
        return PATH_KNOWN.equals(s.path()) ? L_KNOWN : L_RESOLVE;
    }

    private String afterClassify(RoutingState s) {
        return s.decided() ? L_END : L_MATCH;
    }

    /**
     * 定案之后往哪条执行路走。
     *
     * <p>三条路本来就是三个分支，正好用一条条件边表达——这也让"什么情况下走知识线"
     * 从散在 {@code ChatService} 的 if/else 变成了图上看得见的一条边。
     */
    private String afterDecision(RoutingState s) {
        RoutingDecision d = s.decision();
        if (d == null) {
            // 不该发生：走到分派节点说明已经定案了
            log.warn("分派节点上没有决策，按短路处理");
            return L_END;
        }
        if (d.isShortCircuited()) {
            return L_END;
        }
        if (!s.executeEnabled()) {
            // 只要决策不执行（对照测试用的口径），到这儿就收
            return L_END;
        }
        if (d.intent() == Intent.KNOWLEDGE_QUERY) {
            return L_KNOWLEDGE;
        }
        if (d.isComposite()) {
            return L_COMPOSITE;
        }
        return L_AGENT;
    }

    // ---------------- 状态 <-> 决策 ----------------

    /**
     * 把一个终结决策落进状态：先过审计，再把决策整体追加进 DECISIONS 通道。
     *
     * <p>追加整个对象而不是摊平成字段，是为了避开"null 与空值不可区分"的坑
     * （详见 {@link RoutingState} 的类注释），也顺带免掉"重建时漏了一个字段"的风险。
     */
    private Map<String, Object> decisionDelta(RoutingState s, RoutingDecision decision) {
        RoutingDecision audited = steps.auditAndReturn(requestOf(s), decision, s.startedAt());
        return Map.of(RoutingState.DECISIONS, List.of(audited));
    }

    private static RoutingRequest requestOf(RoutingState s) {
        return new RoutingRequest(s.conversationId(), s.aiUserId(), s.memberId(),
                s.roleKey(), s.message());
    }
}
