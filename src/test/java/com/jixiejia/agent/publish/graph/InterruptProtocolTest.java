package com.jixiejia.agent.publish.graph;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 LangGraph4j 1.8.27 的 interrupt / resume 协议<b>钉成断言</b>。
 *
 * <p>为什么值得单独一个测试：这个版本的库<b>没有 interrupt 的文档</b>，
 * 而发布确认是<b>写路径</b>——协议理解错了会写坏数据。将来升级库版本时，
 * 如果协议变了，这个测试会先红，而不是等到线上发布出问题。
 *
 * <p><b>三条结论都是实测出来的</b>（头一版靠"打印 + 肉眼看"得出了两个错误结论，
 * 因为空字符串和 Optional.empty 在打印里长得一样——这也是把它写成断言的原因）：
 *
 * <ol>
 *   <li>{@code interrupt(nodeId, state)} 是<b>节点执行前的预检</b>——
 *       返回非空就挂起，<b>节点体不执行</b>；</li>
 *   <li>挂起时 {@code invoke} <b>返回的是有值的状态</b>（不是空 Optional），
 *       内容是该节点的状态。别拿 {@code isEmpty()} 判挂起，
 *       判据应当看检查点的 {@code next_node_id}（见 {@code MysqlCheckpointSaver.isSuspended}）；</li>
 *   <li>恢复时 {@code GraphInput.resume(payload)} 把 payload <b>并进 state</b>，
 *       并且<b>从挂起的那个节点继续</b>——它之前的节点不会被重跑。</li>
 * </ol>
 */
class InterruptProtocolTest {

    public static final String INPUT = "input";
    public static final String REPLY = "reply";
    public static final String RESUMED = "resumed";

    public static class ProbeState extends AgentState {
        public static final Map<String, Channel<?>> SCHEMA = Map.of(
                INPUT, Channels.<String>base(() -> ""),
                REPLY, Channels.<String>base(() -> ""),
                RESUMED, Channels.<String>base(() -> ""));

        public ProbeState(Map<String, Object> init) {
            super(init);
        }

        public String input() {
            return this.<String>value(INPUT).orElse("");
        }

        public String reply() {
            return this.<String>value(REPLY).orElse("");
        }

        public String resumed() {
            return this.<String>value(RESUMED).orElse("");
        }
    }

    /** 会挂起的节点：预检只读状态，节点体只写状态，都不碰外部世界。 */
    static class InterruptingNode implements AsyncNodeAction<ProbeState>, InterruptibleAction<ProbeState> {

        final AtomicInteger bodyRuns = new AtomicInteger();

        @Override
        public CompletableFuture<Map<String, Object>> apply(ProbeState s) {
            bodyRuns.incrementAndGet();
            return CompletableFuture.completedFuture(Map.of(REPLY, "摘要：" + s.input()));
        }

        @Override
        public Optional<InterruptionMetadata<ProbeState>> interrupt(String nodeId, ProbeState state) {
            return state.resumed().isEmpty()
                    ? Optional.of(InterruptionMetadata.builder(nodeId, state).build())
                    : Optional.empty();
        }
    }

    @Test
    @DisplayName("结论 1+3：预检挂起时节点体不执行；恢复时带着 payload 从挂起节点继续")
    void interruptSuspendsBeforeNodeBody() throws Exception {
        AtomicInteger beforeRuns = new AtomicInteger();
        InterruptingNode node = new InterruptingNode();

        StateGraph<ProbeState> g = new StateGraph<>(ProbeState.SCHEMA,
                new ObjectStreamStateSerializer<>(ProbeState::new));
        // 在挂起节点【之前】插一个节点，用来验证恢复是从哪继续的
        g.addNode("before", AsyncNodeAction.node_async(s -> {
            beforeRuns.incrementAndGet();
            return Map.of();
        }));
        g.addNode("confirm", node);
        g.addEdge(GraphDefinition.START, "before");
        g.addEdge("before", "confirm");
        g.addEdge("confirm", GraphDefinition.END);

        CompiledGraph<ProbeState> graph = g.compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
        RunnableConfig config = RunnableConfig.builder().threadId("t1").build();

        // ① 第一轮：预检拦下，节点体不执行
        graph.invoke(GraphInput.args(Map.of(INPUT, "原话")), config);
        assertThat(node.bodyRuns.get()).as("挂起时节点体不该执行").isZero();
        assertThat(beforeRuns.get()).isEqualTo(1);

        // ② 挂起后状态仍可读——调用方要靠它拿到"该给用户看什么"
        assertThat(graph.getState(config).state().input()).isEqualTo("原话");

        // ③ 恢复：payload 并进状态、节点体执行、且不从 START 重跑
        Optional<ProbeState> resumed = graph.invoke(
                GraphInput.resume(Map.of(RESUMED, "确认")), config);
        assertThat(resumed).isPresent();
        assertThat(resumed.get().resumed()).as("resume 的 payload 应当并进 state").isEqualTo("确认");
        assertThat(node.bodyRuns.get()).as("恢复后节点体执行一次").isEqualTo(1);
        assertThat(beforeRuns.get())
                .as("恢复从挂起节点继续，它之前的节点不该被重跑")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("结论 2：挂起时 invoke 返回的是有值的状态，不能拿 isEmpty() 当挂起判据")
    void suspendedInvokeIsNotEmpty() throws Exception {
        InterruptingNode node = new InterruptingNode();

        StateGraph<ProbeState> g = new StateGraph<>(ProbeState.SCHEMA,
                new ObjectStreamStateSerializer<>(ProbeState::new));
        g.addNode("confirm", node);
        g.addEdge(GraphDefinition.START, "confirm");
        g.addEdge("confirm", GraphDefinition.END);

        CompiledGraph<ProbeState> graph = g.compile(
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
        RunnableConfig config = RunnableConfig.builder().threadId("t2").build();

        Optional<ProbeState> out = graph.invoke(GraphInput.args(Map.of(INPUT, "原话")), config);

        assertThat(out)
                .as("挂起时 invoke 仍然返回有值的状态——这就是为什么判挂起要看检查点的 next_node_id，"
                        + "而不是看 invoke 的返回值")
                .isPresent();
        assertThat(node.bodyRuns.get()).as("但节点体确实没跑").isZero();
    }
}
