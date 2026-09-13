package com.jixiejia.agent.publish.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发布流程状态图的共享状态。
 *
 * <p><b>这张图的 state 里只放 String / Long / Boolean。</b>不是审美问题——
 * 这张图**开了 checkpointer**，每轮挂起都要把整个状态序列化进 {@code ai_graph_checkpoint}。
 * 放 POJO 就得逐个注册序列化器，忘了注册会在"挂起那一刻"才炸（而不是启动时），
 * 排查起来很不直观。发布流程需要跨轮记住的东西恰好多是标量，这个约束不难受。
 *
 * <p><b>和 {@code ai_publish_request} 的分工</b>：
 * 这张图的 state 管"流程跑到哪一步、这一轮该说什么"；
 * 草稿表管"用户填了什么字段"——那是要进后台审核的业务数据，必须留在库里。
 * 两者不是冗余：图状态可以丢（丢了用户重说一遍就行），草稿不能丢。
 */
public class PublishState extends AgentState {

    public static final String CONVERSATION_ID = "conversationId";
    public static final String USER_ID = "userId";
    public static final String MEMBER_ID = "memberId";

    /** 本轮用户原话 */
    public static final String MESSAGE = "message";

    /**
     * 抽取字段时看的文本（最近几轮用户消息 + 当前这句）。
     *
     * <p>为什么要在图上传递而不是节点里现取：它依赖 Spring AI 的 {@code Message} 对象，
     * 而那是不可序列化的，进不了 state；所以在图**外面**算好、以纯字符串传进来。
     */
    public static final String TEXT = "text";

    /** 本轮要回给用户的话 */
    public static final String REPLY = "reply";

    /** handle 之后的去向：await（等用户确认）/ end（本轮到此为止） */
    public static final String PATH = "path";
    public static final String PATH_AWAIT = "await";
    public static final String PATH_END = "end";

    /** 恢复时用户对确认摘要的答复（原话） */
    public static final String CONFIRMATION = "confirmation";

    /** awaitConfirm 节点判出的动作：submit / cancel / amend，由条件边读取 */
    public static final String ACTION = "action";
    public static final String ACTION_SUBMIT = "submit";
    public static final String ACTION_CANCEL = "cancel";
    public static final String ACTION_AMEND = "amend";

    public static final Map<String, Channel<?>> SCHEMA = buildSchema();

    public PublishState(Map<String, Object> init) {
        super(init);
    }

    public String conversationId() {
        return this.<String>value(CONVERSATION_ID).orElse("");
    }

    public Long userId() {
        Long v = this.<Long>value(USER_ID).orElse(0L);
        return (v == null || v == 0L) ? null : v;
    }

    public String message() {
        return this.<String>value(MESSAGE).orElse("");
    }

    public String text() {
        return this.<String>value(TEXT).orElse("");
    }

    public String reply() {
        return this.<String>value(REPLY).orElse("");
    }

    public String path() {
        return this.<String>value(PATH).orElse(PATH_END);
    }

    public String confirmation() {
        return this.<String>value(CONFIRMATION).orElse("");
    }

    public String action() {
        return this.<String>value(ACTION).orElse("");
    }

    /** 本轮的抽取文本；为空时退回原话（历史取不到也不该让抽取拿不到输入）。 */
    public String effectiveText() {
        String t = text();
        return t.isBlank() ? message() : t;
    }

    private static Map<String, Channel<?>> buildSchema() {
        Map<String, Channel<?>> schema = new LinkedHashMap<>();
        schema.put(CONVERSATION_ID, Channels.<String>base(() -> ""));
        schema.put(USER_ID, Channels.<Long>base(() -> 0L));
        schema.put(MEMBER_ID, Channels.<Long>base(() -> 0L));
        schema.put(MESSAGE, Channels.<String>base(() -> ""));
        schema.put(TEXT, Channels.<String>base(() -> ""));
        schema.put(REPLY, Channels.<String>base(() -> ""));
        schema.put(PATH, Channels.<String>base(() -> PATH_END));
        schema.put(CONFIRMATION, Channels.<String>base(() -> ""));
        schema.put(ACTION, Channels.<String>base(() -> ""));
        return Map.copyOf(schema);
    }
}
