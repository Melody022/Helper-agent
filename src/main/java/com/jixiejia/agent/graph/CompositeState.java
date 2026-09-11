package com.jixiejia.agent.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨域综合子图的共享状态。
 *
 * <p>各域节点跑完后把结果写进 {@link #RESULTS}，末端综合节点从那里取。
 *
 * <p>{@code RESULTS} 用的是 <b>appender 通道</b>而不是普通通道：多个域节点是并行跑的，
 * 普通通道是"后写覆盖先写"，并行写同一个 key 会互相覆盖、只剩最后一个域的结果。
 * appender 通道把每次写入追加进同一个 List，并行安全。
 *
 * <p>每个元素是"【领域名】+ 该域的回答"这样一段纯文本，而不是结构化对象——
 * 状态里只放 String / List&lt;String&gt;，默认的 Java 序列化就能处理，
 * 不必再给 POJO 单独注册 JSON 序列化器（教程里踩过这个坑）。
 */
public class CompositeState extends AgentState {

    /** 用户原话（已做指代消解） */
    public static final String MESSAGE = "message";

    /** 调用者角色，用于解析各 Agent 的工具白名单 */
    public static final String ROLE = "role";

    /** 本轮要跑的 Agent 列表 */
    public static final String AGENT_KEYS = "agentKeys";

    /** 各域结果，appender 通道 */
    public static final String RESULTS = "results";

    /** 综合后的最终回答 */
    public static final String ANSWER = "answer";

    /** 显式声明通道，避免未声明的 key 被默认通道按"覆盖"处理 */
    public static final Map<String, Channel<?>> SCHEMA = buildSchema();

    public CompositeState(Map<String, Object> init) {
        super(init);
    }

    public String message() {
        return this.<String>value(MESSAGE).orElse("");
    }

    public String role() {
        return this.<String>value(ROLE).orElse("USER");
    }

    public List<String> agentKeys() {
        return valueList(AGENT_KEYS);
    }

    public List<String> results() {
        return valueList(RESULTS);
    }

    public String answer() {
        return this.<String>value(ANSWER).orElse("");
    }

    @SuppressWarnings("unchecked")
    private List<String> valueList(String key) {
        return this.<List<String>>value(key).orElse(List.of());
    }

    private static Map<String, Channel<?>> buildSchema() {
        Map<String, Channel<?>> schema = new LinkedHashMap<>();
        schema.put(MESSAGE, Channels.<String>base(() -> ""));
        schema.put(ROLE, Channels.<String>base(() -> "USER"));
        schema.put(AGENT_KEYS, Channels.<List<String>>base(ArrayList::new));
        schema.put(RESULTS, Channels.appender(ArrayList::new));
        schema.put(ANSWER, Channels.<String>base(() -> ""));
        return Map.copyOf(schema);
    }
}
