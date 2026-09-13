package com.jixiejia.agent.router;

import com.jixiejia.agent.classify.ClassifyLayer;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.classify.IntentResult;
import com.jixiejia.agent.rag.KnowledgeAnswerService;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由状态图的共享状态。
 *
 * <p>原先是 {@code MsgRouter.doRoute()} 里的局部变量，现在显式声明成图上的一条条通道——
 * 这样"哪一步产生了什么、下一步读什么"从方法体里搬到了一张声明式的表上。
 *
 * <p><b>两条踩出来的硬约束，改这个类之前先读：</b>
 *
 * <ol>
 *   <li><b>通道的默认值不能是 {@code null}。</b>
 *       {@code AgentStateFactory.initialDataFromSchema()} 用 {@code Collectors} 收集各通道的
 *       默认值，收集器不接受 null，一写 {@code () -> null} 就在 {@code invoke()} 时抛
 *       {@code NullPointerException}——而且堆栈里全是 stream 的栈帧，看不出是哪条通道的锅。
 *       所以：
 *       <ul>
 *         <li>String 通道一律默认 {@code ""}，需要区分"没写过"时用 {@link #orNull} 转回去；</li>
 *         <li>Long 通道默认 {@code 0}——{@link #aiUserId()} / {@link #memberId()} 里把 0 还原成 null。
 *             <b>0 是安全的哨兵</b>：这两个都是自增主键，从 1 开始，不可能是 0；</li>
 *         <li>枚举通道默认取一个"最保守"的取值（UNKNOWN / FALLBACK），反正终结时会被覆盖。</li>
 *       </ul>
 *   </li>
 *   <li><b>终结结果用 {@code appender} 通道装整个 {@link RoutingDecision} 对象，不逐字段摊平。</b>
 *       原因还是 null：短路决策的 {@code reply} / {@code resolvedText} / {@code classifyLayer}
 *       在原实现里就是 null，摊平进通道后没法与"没写过"区分，重建出来必然对不上。
 *       装整个对象就绕开了这个问题——而且它<b>天然等价</b>，不存在"重建逻辑写漏一个字段"的风险。
 *       appender 的默认值是空 List（非 null），正好可用；路由是单线的，这个 List 里要么空、
 *       要么恰好一个元素。
 *   </li>
 * </ol>
 *
 * <p><b>为什么其余通道都用 {@code base}（覆盖）而不是 appender：</b>路由链是<b>单线</b>的——
 * 每一步只有一个节点会执行，同一个 key 不存在被并发写的情况。appender 只在并行扇出时才需要
 * （见 {@code graph/CompositeState} 的 RESULTS 通道），那是跨域综合子图的事，不在这一层。
 */
public class RoutingState extends AgentState {

    // ---------- 输入 ----------

    public static final String CONVERSATION_ID = "conversationId";
    public static final String AI_USER_ID = "aiUserId";
    public static final String MEMBER_ID = "memberId";
    public static final String ROLE_KEY = "roleKey";
    public static final String MESSAGE = "message";

    // ---------- ③ 扫描（粘性 + 关键词 + 跨域）----------

    /** 上一轮的粘性记录；不拆成两个字段是因为下游要的是完整的 {@code StickySession} */
    public static final String STICKY = "sticky";

    /** 关键词层定案结果；为空表示关键词没定案（此时才需要叫模型） */
    public static final String KEYWORD_RESULT = "keywordResult";

    public static final String CROSS_AGENTS = "crossAgents";

    /** 扫描后要走哪条路，由条件边读它决定下一个节点 */
    public static final String PATH = "path";

    /** 关键词层已定案，交给 known 节点（不叫模型） */
    public static final String PATH_KNOWN = "known";

    /** 关键词没命中也不像追问，继续叫模型 */
    public static final String PATH_FALLTHROUGH = "fallthrough";

    // ---------- ④ 指代消解 ----------

    public static final String RECENT_TURNS = "recentTurns";
    public static final String RESOLVED_TEXT = "resolvedText";
    public static final String REFERENCE_NOTE = "referenceNote";

    // ---------- ⑤ 三层意图分类 ----------

    public static final String INTENT = "intent";
    public static final String CONFIDENCE = "confidence";
    public static final String CLASSIFY_LAYER = "classifyLayer";
    public static final String INTENT_REASON = "intentReason";

    // ---------- 产出 ----------

    /**
     * 本轮定案的 {@link RoutingDecision}。
     *
     * <p>用 appender 通道（默认空 List）装整个对象：路由单线执行，所以要么空、要么一个元素。
     * 它同时充当"是否已定案"的标记——比在每条边上重复判断业务条件更不容易漏。
     */
    public static final String DECISIONS = "decisions";

    /** 执行之后要交给用户的回答（短路时就是决策里的 reply） */
    public static final String ANSWER = "answer";

    /** 实际执行了谁：单 Agent 的 key、跨域的多个 key、或"Knowledge(检索+证据闸)" */
    public static final String EXECUTED_AGENT_KEY = "executedAgentKey";

    /** 知识问答的溯源依据，前端拿它渲染可点角标 */
    public static final String SOURCES = "sources";

    /**
     * 定案之后要不要真的执行。
     *
     * <p>存在的理由只有一个：{@code SameDecisionTest} 要拿<b>只判定不执行</b>的口径
     * 去和旧的 {@code MsgRouter.route()} 逐字段比对——旧实现本来就不执行。
     * 如果那条路也会真去调 Agent，对照测试会变成"跑 17 遍真实模型"，
     * 既慢又会把模型的不确定性引进来。
     */
    public static final String EXECUTE = "execute";

    // ---------- 控制 ----------

    /** 链路起点毫秒时间戳，审计的 latencyMs 由它算出来 */
    public static final String STARTED_AT = "startedAt";

    /** 显式声明通道，避免未声明的 key 被默认通道按"覆盖"处理 */
    public static final Map<String, Channel<?>> SCHEMA = buildSchema();

    public RoutingState(Map<String, Object> init) {
        super(init);
    }

    // ---------- 取值 ----------

    public String conversationId() {
        return this.<String>value(CONVERSATION_ID).orElse("");
    }

    /** 未登录时为 null——0 是"没有"的哨兵值，见类注释 */
    public Long aiUserId() {
        return orNull(this.<Long>value(AI_USER_ID).orElse(0L));
    }

    /** 未绑定会员时为 null */
    public Long memberId() {
        return orNull(this.<Long>value(MEMBER_ID).orElse(0L));
    }

    public String roleKey() {
        return this.<String>value(ROLE_KEY).orElse("USER");
    }

    public String message() {
        return this.<String>value(MESSAGE).orElse("");
    }

    public StickySessionStore.StickySession sticky() {
        return firstOrNull(this.<List<StickySessionStore.StickySession>>value(STICKY).orElse(List.of()));
    }

    public IntentResult keywordResult() {
        return firstOrNull(this.<List<IntentResult>>value(KEYWORD_RESULT).orElse(List.of()));
    }

    public String path() {
        return this.<String>value(PATH).orElse(PATH_FALLTHROUGH);
    }

    /** 最近几轮对话文本，无历史时为 null（指代消解的输入） */
    public String recentTurns() {
        return orNull(this.<String>value(RECENT_TURNS).orElse(""));
    }

    public String resolvedText() {
        return this.<String>value(RESOLVED_TEXT).orElse("");
    }

    /** 指代消解的说明，无说明时为 null（拼 reason 时要靠它决定加不加前缀） */
    public String referenceNote() {
        return orNull(this.<String>value(REFERENCE_NOTE).orElse(""));
    }

    public Intent intent() {
        return this.<Intent>value(INTENT).orElse(null);
    }

    public double confidence() {
        return this.<Double>value(CONFIDENCE).orElse(0.0);
    }

    public ClassifyLayer classifyLayer() {
        return this.<ClassifyLayer>value(CLASSIFY_LAYER).orElse(null);
    }

    /** 分类依据，无依据时为 null */
    public String intentReason() {
        return orNull(this.<String>value(INTENT_REASON).orElse(""));
    }

    public long startedAt() {
        return this.<Long>value(STARTED_AT).orElse(0L);
    }

    public List<RoutingDecision> decisions() {
        return this.<List<RoutingDecision>>value(DECISIONS).orElse(List.of());
    }

    /** 本轮是否已经定案。 */
    public boolean decided() {
        return !decisions().isEmpty();
    }

    /** 取本轮定案的决策；未定案时返回 null。 */
    public RoutingDecision decision() {
        List<RoutingDecision> all = decisions();
        return all.isEmpty() ? null : all.get(0);
    }

    public String answer() {
        return this.<String>value(ANSWER).orElse("");
    }

    public String executedAgentKey() {
        return this.<String>value(EXECUTED_AGENT_KEY).orElse(null);
    }

    public List<KnowledgeAnswerService.Source> sources() {
        return this.<List<KnowledgeAnswerService.Source>>value(SOURCES).orElse(List.of());
    }

    public boolean executeEnabled() {
        return this.<Boolean>value(EXECUTE).orElse(Boolean.TRUE);
    }

    // ---------- 工具方法 ----------

    /** 空串还原成 null——用来区分"没写过"和"写了个空串"。 */
    static String orNull(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }

    /** 取"可空对象通道"里的唯一元素；空 List 表示这个对象本轮不存在。 */
    private static <T> T firstOrNull(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private static Long orNull(Long v) {
        return (v == null || v == 0L) ? null : v;
    }

    /** 拼状态增量。 */
    static Map<String, Object> delta(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                out.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return out;
    }

    private static Map<String, Channel<?>> buildSchema() {
        Map<String, Channel<?>> schema = new LinkedHashMap<>();

        schema.put(CONVERSATION_ID, Channels.<String>base(() -> ""));
        schema.put(AI_USER_ID, Channels.<Long>base(() -> 0L));
        schema.put(MEMBER_ID, Channels.<Long>base(() -> 0L));
        schema.put(ROLE_KEY, Channels.<String>base(() -> "USER"));
        schema.put(MESSAGE, Channels.<String>base(() -> ""));

        schema.put(STICKY, Channels.<List<StickySessionStore.StickySession>>appender(
                () -> new ArrayList<>()));
        schema.put(KEYWORD_RESULT, Channels.<List<IntentResult>>appender(() -> new ArrayList<>()));
        schema.put(CROSS_AGENTS, Channels.<List<String>>base(() -> new ArrayList<>()));
        schema.put(PATH, Channels.<String>base(() -> PATH_FALLTHROUGH));

        schema.put(RECENT_TURNS, Channels.<String>base(() -> ""));
        schema.put(RESOLVED_TEXT, Channels.<String>base(() -> ""));
        schema.put(REFERENCE_NOTE, Channels.<String>base(() -> ""));

        schema.put(INTENT, Channels.<Intent>base(() -> Intent.UNKNOWN));
        schema.put(CONFIDENCE, Channels.<Double>base(() -> 0.0));
        schema.put(CLASSIFY_LAYER, Channels.<ClassifyLayer>base(() -> ClassifyLayer.FALLBACK));
        schema.put(INTENT_REASON, Channels.<String>base(() -> ""));

        // 唯一一个 appender：装整个决策对象，见类注释第 2 条
        schema.put(DECISIONS, Channels.<List<RoutingDecision>>appender(() -> new ArrayList<>()));

        schema.put(ANSWER, Channels.<String>base(() -> ""));
        schema.put(EXECUTED_AGENT_KEY, Channels.<String>base(() -> ""));
        schema.put(SOURCES, Channels.<List<KnowledgeAnswerService.Source>>base(
                () -> new ArrayList<>()));
        schema.put(EXECUTE, Channels.<Boolean>base(() -> Boolean.TRUE));

        schema.put(STARTED_AT, Channels.<Long>base(() -> 0L));

        return Map.copyOf(schema);
    }
}
