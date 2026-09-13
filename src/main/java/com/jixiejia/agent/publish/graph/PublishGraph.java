package com.jixiejia.agent.publish.graph;

import com.jixiejia.agent.persistence.entity.ai.AiPublishRequest;
import com.jixiejia.agent.persistence.mapper.ai.AiGraphCheckpointMapper;
import com.jixiejia.agent.publish.PublishFormService;
import com.jixiejia.agent.publish.PublishTarget;
import com.jixiejia.agent.publish.PublishWriteService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.utils.EdgeMappings;
import com.jixiejia.agent.graph.MysqlCheckpointSaver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布流程的状态图：把"等用户确认"做成图上一个真正的<b>挂起点</b>。
 *
 * <pre>
 *  START → handle ─┬─(字段没齐)──────────────────────────→ END
 *                  └─(字段齐了、摘要已给) → awaitConfirm
 *                                            │
 *                                     ☆ interrupt 挂起 ☆
 *                                            │ (用户下一轮回复)
 *                                            ▼
 *                            ┌───────────────┼───────────────┐
 *                        确认发布         取消            补充/修改
 *                            ▼               ▼               ▼
 *                         submit → END    cancel → END    handle（回到收集）
 * </pre>
 *
 * <p><b>为什么值得从手写状态机换成图。</b>原来的做法是在 {@code PublishAgent} 里
 * 用 {@code isAwaitingConfirm(draft)} 这个<b>数据库字段</b>表示"正在等确认"，
 * 流程位置是<b>推算出来的</b>；现在它是图上一个明确的挂起节点，
 * 读代码时看得见、查问题时也能问图"你卡在哪一步"。
 *
 * <p><b>checkpointer 在这里不是可选项。</b>图挂起后，下一轮请求必须从挂起点恢复，
 * 而"挂在哪"必须有个地方存。所以本图编译时挂了
 * {@link MysqlCheckpointSaver}——它把状态写进 {@code ai_graph_checkpoint}，
 * 进程重启也不丢。用库自带的 MemorySaver 就等于把 {@code PublishFormService}
 * 注释里批评过的"状态放内存里，重启就从头再来"重演一遍。
 *
 * <p><b>interrupt 的协议</b>（这个版本的库没有文档，是实验测出来的，见
 * {@code InterruptProbeTest}）：{@code interrupt(nodeId, state)} 是<b>节点执行前的预检</b>——
 * 返回非空就挂起、节点体不执行；恢复时 {@code GraphInput.resume(payload)} 把答复并进 state，
 * 预检看到答复返回空、节点体才跑。所以：
 * <b>预检方法里不能有副作用，节点体也不能有——它在恢复时会被重新执行一遍。</b>
 * 本图里 awaitConfirm 的预检只读 {@code confirmation}，节点体只做分类，都不碰外部状态。
 *
 * <p><b>草稿仍然留在 {@code ai_publish_request}</b>：那是要进后台审核的业务数据，
 * 不能只活在图的 state 里。两者分工是"流程位置" vs "业务数据"。
 */
@Slf4j
@Component
public class PublishGraph {

    private static final String N_HANDLE = "handle";
    private static final String N_AWAIT = "awaitConfirm";
    private static final String N_SUBMIT = "submit";
    private static final String N_CANCEL = "cancel";

    private static final String L_AWAIT = "await";
    private static final String L_END = "end";
    private static final String L_SUBMIT = "submit";
    private static final String L_CANCEL = "cancel";
    private static final String L_AMEND = "amend";

    /** 确认词。用户看完摘要说这些就是同意提交 */
    private static final Set<String> CONFIRM_WORDS = Set.of(
            "确认", "确定", "提交", "没问题", "可以", "对的", "是的", "没问题了");

    /** 取消词 */
    private static final Set<String> CANCEL_WORDS = Set.of(
            "取消", "算了", "不发了", "先不", "放弃");

    /** 确认令牌的样式：8 位大写字母数字 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\b[A-Z0-9]{8}\\b");

    private static final String ASK_TARGET_REPLY = """
            你想发布的是「出租」还是「求租」？
              · 出租：把你的设备挂出去租给别人
              · 求租：你这边需要机器，发个需求
            告诉我是哪种，我帮你填。""";

    /** 发布是写操作：最多 4 个节点（handle → await → submit/cancel），留一倍余量 */
    private static final int RECURSION_LIMIT = 8;

    private final PublishFormService formService;
    private final PublishWriteService writeService;
    private final AiGraphCheckpointMapper checkpointMapper;

    private volatile CompiledGraph<PublishState> compiled;
    private volatile MysqlCheckpointSaver saver;

    public PublishGraph(PublishFormService formService,
                        PublishWriteService writeService,
                        AiGraphCheckpointMapper checkpointMapper) {
        this.formService = formService;
        this.writeService = writeService;
        this.checkpointMapper = checkpointMapper;
    }

    @PostConstruct
    void build() {
        try {
            ObjectStreamStateSerializer<PublishState> serializer =
                    new ObjectStreamStateSerializer<>(PublishState::new);
            MysqlCheckpointSaver checkpointSaver = new MysqlCheckpointSaver(checkpointMapper, serializer);

            StateGraph<PublishState> graph = new StateGraph<>(PublishState.SCHEMA, serializer);
            graph.addNode(N_HANDLE, AsyncNodeAction.node_async(this::handleNode));
            graph.addNode(N_AWAIT, new AwaitConfirmNode());
            graph.addNode(N_SUBMIT, AsyncNodeAction.node_async(this::submitNode));
            graph.addNode(N_CANCEL, AsyncNodeAction.node_async(this::cancelNode));

            graph.addEdge(GraphDefinition.START, N_HANDLE);
            graph.addConditionalEdges(N_HANDLE, AsyncEdgeAction.edge_async(this::afterHandle),
                    EdgeMappings.builder().to(N_AWAIT, L_AWAIT).toEND(L_END).build());
            graph.addConditionalEdges(N_AWAIT, AsyncEdgeAction.edge_async(this::afterAwait),
                    EdgeMappings.builder()
                            .to(N_SUBMIT, L_SUBMIT)
                            .to(N_CANCEL, L_CANCEL)
                            .to(N_HANDLE, L_AMEND)
                            .build());
            graph.addEdge(N_SUBMIT, GraphDefinition.END);
            graph.addEdge(N_CANCEL, GraphDefinition.END);

            this.saver = checkpointSaver;
            this.compiled = graph.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .recursionLimit(RECURSION_LIMIT)
                    .build());
            log.info("发布状态图已构建：4 个节点、2 条条件边、1 个挂起点；检查点落 MySQL");

        } catch (Exception e) {
            log.error("发布状态图构建失败，发布功能不可用", e);
        }
    }

    /** 图是否可用。 */
    public boolean available() {
        return compiled != null;
    }

    /**
     * 这个会话是否正挂在"等确认"上。
     *
     * <p>判据是检查点里的 {@code next_node_id} 有没有值——它只在图被 interrupt 挂起时才会被写上。
     * 调用方据此决定"开新一轮"还是"恢复上一轮"。
     */
    public boolean isAwaiting(String conversationId) {
        MysqlCheckpointSaver s = saver;
        return s != null && conversationId != null && s.isSuspended(conversationId);
    }

    /** 开新一轮：图从 START 跑。 */
    public String start(PublishInput in) {
        if (compiled == null) {
            return "发布功能暂时不可用，请稍后再试或回复\"转人工\"。";
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(PublishState.CONVERSATION_ID, in.conversationId());
        args.put(PublishState.USER_ID, in.userId() == null ? 0L : in.userId());
        args.put(PublishState.MEMBER_ID, in.memberId() == null ? 0L : in.memberId());
        args.put(PublishState.MESSAGE, in.message());
        args.put(PublishState.TEXT, in.text());
        return run(GraphInput.args(args), in.conversationId());
    }

    /** 从挂起点恢复：用户对确认摘要的答复进来。 */
    public String resume(PublishInput in) {
        if (compiled == null) {
            return "发布功能暂时不可用，请稍后再试或回复\"转人工\"。";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        // confirmation 用原话（判词用），message/text 用抽取文本（补充修改时要重新抽字段）
        payload.put(PublishState.CONFIRMATION, in.message());
        payload.put(PublishState.MESSAGE, in.message());
        payload.put(PublishState.TEXT, in.text());
        return run(GraphInput.resume(payload), in.conversationId());
    }

    private String run(GraphInput input, String conversationId) {
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        try {
            // ⚠️ 挂起时 invoke 返回的也是【有值】的状态（不是空 Optional），
            // 所以这里不能拿它判"跑完没跑完"——判据一律用检查点的 next_node_id。
            String reply = compiled.invoke(input, config)
                    .map(PublishState::reply)
                    .orElse("");

            if (reply == null || reply.isBlank()) {
                // 兜底：宁可让用户看到一句"该怎么继续"，也不要回一句空的
                log.warn("发布图这一轮没有产出回复内容，conversationId={}", conversationId);
                reply = "请回复「确认发布」提交，或直接告诉我需要修改哪里。";
            }

            // 图跑完了（没挂在挂起点上）就把这条线程的检查点清掉，否则这张表会一直涨
            releaseIfFinished(conversationId);
            return reply;

        } catch (Exception e) {
            log.error("发布状态图执行失败，conversationId={}", conversationId, e);
            return "发布流程出了点问题，请稍后再试，或者回复\"转人工\"让客服帮你发布。";
        }
    }

    private void releaseIfFinished(String conversationId) {
        MysqlCheckpointSaver s = saver;
        if (s != null && !s.isSuspended(conversationId)) {
            s.clear(conversationId);
        }
    }

    // ---------------- 节点 ----------------

    /**
     * 一轮正常推进：判目标 → 抽字段 → 校验 → 要么追问、要么给确认摘要。
     *
     * <p><b>开头的守卫是防什么的。</b>{@code confirmation} 非空说明这一轮是"用户在回复确认摘要"。
     * 正常路径下恢复<b>从挂起节点继续</b>、handle 不会被重入（见 {@code InterruptProtocolTest}），
     * "补充修改"那条回到 handle 时答复也已经用掉并清空了，所以守卫平时不触发。
     *
     * <p>留着它是防一类极难排查的事故：**万一重新 advance，就会重新生成确认令牌**，
     * 把用户刚收到的那个当场作废——症状是"用户明明抄对了却回'确认码对不上'"，
     * 从这句话完全联想不到是这里。图的结构或库的 resume 语义一旦变化，
     * 这个守卫就是最后一道防线。
     */
    private Map<String, Object> handleNode(PublishState s) {
        if (!s.confirmation().isEmpty()) {
            // 恢复的一轮：不抽字段、不重新生成令牌，直接去让 awaitConfirm 处理答复
            return delta(PublishState.PATH, PublishState.PATH_AWAIT);
        }

        if (s.userId() == null) {
            return delta(PublishState.REPLY, "发布需要先登录。请登录后再让我帮你发布信息。",
                    PublishState.PATH, PublishState.PATH_END);
        }

        String message = s.message().trim();
        AiPublishRequest draft = formService.findActiveDraft(s.conversationId());

        PublishTarget target = detectTarget(message, draft);
        if (target == null) {
            return delta(PublishState.REPLY, ASK_TARGET_REPLY,
                    PublishState.PATH, PublishState.PATH_END);
        }

        boolean starting = draft == null || !target.table().equals(draft.getTargetTable());
        PublishFormService.Outcome outcome = formService.advance(
                s.userId(), s.conversationId(), target, s.effectiveText(), starting);

        return delta(PublishState.REPLY, outcome.reply(),
                PublishState.PATH, outcome.awaitingConfirm() ? PublishState.PATH_AWAIT : PublishState.PATH_END);
    }

    /** 用户确认后落库。校验令牌、防重复提交。 */
    private Map<String, Object> submitNode(PublishState s) {
        AiPublishRequest draft = formService.findActiveDraft(s.conversationId());
        if (draft == null) {
            return delta(PublishState.REPLY,
                    "这条发布已经不在了（可能已经提交或过期）。要重新发布的话再跟我说一次就行。");
        }
        if (!formService.isTokenValid(draft)) {
            formService.expire(draft);
            return delta(PublishState.REPLY, """
                    这条发布请求超过时间没确认，我这边先作废了（过了有效期再提交会有风险）。
                    要重新发布的话再跟我说一次就行。""");
        }

        String provided = extractToken(s.confirmation());
        if (provided != null && !provided.equalsIgnoreCase(draft.getConfirmToken())) {
            // 用户抄错了令牌——说明他可能在看另一条发布的摘要
            return delta(PublishState.REPLY,
                    "这个确认码对不上，请核对一下是不是复制错了。\n"
                            + "正确的确认码是 " + draft.getConfirmToken() + "。");
        }

        Map<String, Object> payload = formService.readPayload(draft);
        PublishWriteService.WriteResult result = writeService.submit(draft, payload);
        return delta(PublishState.REPLY, result.message());
    }

    /** 用户取消，作废草稿。 */
    private Map<String, Object> cancelNode(PublishState s) {
        AiPublishRequest draft = formService.findActiveDraft(s.conversationId());
        if (draft != null) {
            formService.cancel(draft);
        }
        return delta(PublishState.REPLY, "好的，这条发布已经取消了。需要重新发布随时告诉我。");
    }

    /**
     * 挂起点：等用户对确认摘要作答。
     *
     * <p>两个方法都<b>不能有副作用</b>——预检每轮都跑，节点体在恢复时会被重新执行。
     */
    private final class AwaitConfirmNode
            implements AsyncNodeAction<PublishState>, InterruptibleAction<PublishState> {

        @Override
        public CompletableFuture<Map<String, Object>> apply(PublishState s) {
            // 只在恢复后执行：把用户的答复分类成 提交 / 取消 / 补充修改
            String answer = s.confirmation();
            String action;
            if (containsAny(answer, CANCEL_WORDS)) {
                action = PublishState.ACTION_CANCEL;
            } else if (containsAny(answer, CONFIRM_WORDS)) {
                action = PublishState.ACTION_SUBMIT;
            } else {
                action = PublishState.ACTION_AMEND;
            }
            log.debug("发布确认答复「{}」→ {}", answer, action);

            // 答复用掉就清空：否则走"补充修改"回到 handle 时，守卫会以为还在恢复，
            // 新摘要永远等不到挂起——图会直接跑完，用户看到的摘要没有"确认"这一步
            Map<String, Object> delta = delta(PublishState.ACTION, action);
            delta.put(PublishState.CONFIRMATION, "");
            return CompletableFuture.completedFuture(delta);
        }

        @Override
        public Optional<InterruptionMetadata<PublishState>> interrupt(String nodeId, PublishState state) {
            if (!state.confirmation().isEmpty()) {
                return Optional.empty();
            }
            log.debug("发布图挂起，等用户确认：conversationId={}", state.conversationId());
            return Optional.of(InterruptionMetadata.builder(nodeId, state).build());
        }
    }

    // ---------------- 条件边 ----------------

    private String afterHandle(PublishState s) {
        return PublishState.PATH_AWAIT.equals(s.path()) ? L_AWAIT : L_END;
    }

    private String afterAwait(PublishState s) {
        return switch (s.action()) {
            case PublishState.ACTION_SUBMIT -> L_SUBMIT;
            case PublishState.ACTION_CANCEL -> L_CANCEL;
            default -> L_AMEND;
        };
    }

    // ---------------- 辅助 ----------------

    /**
     * 判断要发布的是出租还是求租。
     *
     * <p>优先看这句话里的词；说不清时沿用草稿的类型（用户可能只是在补充信息）。
     */
    private static PublishTarget detectTarget(String text, AiPublishRequest draft) {
        boolean wantsRent = text.contains("求租") || text.contains("找机器")
                || text.contains("找设备") || text.contains("找活") || text.contains("需要机器");
        boolean wantsLease = text.contains("出租") || text.contains("租出去")
                || text.contains("挂出去") || text.contains("往外租");

        if (wantsRent && !wantsLease) {
            return PublishTarget.QIUZU;
        }
        if (wantsLease && !wantsRent) {
            return PublishTarget.CHUZU;
        }
        if (draft != null) {
            return PublishTarget.byTable(draft.getTargetTable());
        }
        return null;
    }

    private static boolean containsAny(String text, Set<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 从"确认发布 A3F2B1C9"里把令牌抠出来。没写令牌时返回 null（允许只说"确认"）。 */
    private static String extractToken(String text) {
        Matcher m = TOKEN_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static Map<String, Object> delta(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    /** 一轮发布的输入。 */
    public record PublishInput(String conversationId, Long userId, Long memberId,
                               String message, String text) {
    }

    /** 供测试与诊断：这张图一共几个节点。 */
    public List<String> nodeNames() {
        return List.of(N_HANDLE, N_AWAIT, N_SUBMIT, N_CANCEL);
    }
}
