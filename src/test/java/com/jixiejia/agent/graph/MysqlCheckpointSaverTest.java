package com.jixiejia.agent.graph;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiGraphCheckpoint;
import com.jixiejia.agent.persistence.mapper.ai.AiGraphCheckpointMapper;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.InterruptibleAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL 检查点存取器。
 *
 * <p>重点验一件事：<b>挂起状态真的落到了数据库里</b>。
 * 这是"进程重启后还能接着确认发布"的全部依据——如果检查点只在内存里，
 * 那和 {@code PublishFormService} 注释里批评过的"状态放内存里，重启就从头再来"没区别。
 */
@SpringBootTest
class MysqlCheckpointSaverTest {

    private static final String THREAD_PREFIX = "test-cp-";

    public static final String INPUT = "input";
    public static final String REPLY = "reply";
    public static final String RESUMED = "resumed";

    public static class CpState extends AgentState {
        public static final Map<String, Channel<?>> SCHEMA = Map.of(
                INPUT, Channels.<String>base(() -> ""),
                REPLY, Channels.<String>base(() -> ""),
                RESUMED, Channels.<String>base(() -> ""));

        public CpState(Map<String, Object> init) {
            super(init);
        }

        public String input() {
            return this.<String>value(INPUT).orElse("");
        }

        public String resumed() {
            return this.<String>value(RESUMED).orElse("");
        }
    }

    @Autowired
    private AiGraphCheckpointMapper mapper;

    @AfterEach
    void cleanUp() {
        mapper.delete(Wrappers.<AiGraphCheckpoint>lambdaQuery()
                .likeRight(AiGraphCheckpoint::getThreadId, THREAD_PREFIX));
    }

    /** 编一张"跑到 confirm 会挂起"的最小图，检查点交给 MySQL 存取器。 */
    private CompiledGraph<CpState> graph(MysqlCheckpointSaver saver) throws Exception {
        StateGraph<CpState> g = new StateGraph<>(CpState.SCHEMA,
                new ObjectStreamStateSerializer<>(CpState::new));
        g.addNode("confirm", new AsyncNodeAction<CpState>() {
            @Override
            public CompletableFuture<Map<String, Object>> apply(CpState s) {
                return CompletableFuture.completedFuture(Map.of(REPLY, "摘要：" + s.input()));
            }
        });
        g.addEdge(GraphDefinition.START, "confirm");
        g.addEdge("confirm", GraphDefinition.END);
        return g.compile(CompileConfig.builder().checkpointSaver(saver).build());
    }

    @Test
    @DisplayName("挂起时检查点写进 MySQL；恢复时能读回来；走完能清掉")
    void checkpointRoundTrip() throws Exception {
        ObjectStreamStateSerializer<CpState> serializer =
                new ObjectStreamStateSerializer<>(CpState::new);
        MysqlCheckpointSaver saver = new MysqlCheckpointSaver(mapper, serializer);
        CompiledGraph<CpState> graph = graph(saver);

        String threadId = THREAD_PREFIX + UUID.randomUUID();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        // ① 正常跑完 → 检查点落库，但 next_node_id 应当是 __END__，不算"挂起"
        graph.invoke(GraphInput.args(Map.of(INPUT, "原话")), config);

        List<AiGraphCheckpoint> rows = rows(threadId);
        assertThat(rows).as("跑完一轮应当留下检查点").isNotEmpty();
        assertThat(saver.isSuspended(threadId))
                .as("跑完的检查点 next_node_id 是 __END__，不能被当成挂起——"
                        + "判错会让下一轮走恢复分支，流程整个错位")
                .isFalse();

        // ② 挂起一张真的会 interrupt 的图 → next_node_id 是真实节点
        StateGraph<CpState> g2 = new StateGraph<>(CpState.SCHEMA, serializer);
        g2.addNode("confirm", new InterruptingNode());
        g2.addEdge(GraphDefinition.START, "confirm");
        g2.addEdge("confirm", GraphDefinition.END);
        CompiledGraph<CpState> suspending = g2.compile(
                CompileConfig.builder().checkpointSaver(saver).build());

        String thread2 = THREAD_PREFIX + UUID.randomUUID();
        RunnableConfig config2 = RunnableConfig.builder().threadId(thread2).build();
        // 挂起时 invoke 仍然返回有值的状态，所以这里不能用返回值判挂起
        suspending.invoke(GraphInput.args(Map.of(INPUT, "原话")), config2);

        assertThat(saver.isSuspended(thread2)).as("挂起的线程应当被判为 suspended").isTrue();
        assertThat(rows(thread2)).isNotEmpty();

        // ③ 恢复：状态从 MySQL 读回来，上一轮的输入还在
        assertThat(suspending.invoke(GraphInput.resume(Map.of(RESUMED, "确认")), config2).get().resumed())
                .isEqualTo("确认");

        // ④ 清掉
        saver.clear(thread2);
        assertThat(rows(thread2)).as("清完之后这条线程不该再有检查点").isEmpty();
        assertThat(saver.isSuspended(thread2)).isFalse();
    }

    private List<AiGraphCheckpoint> rows(String threadId) {
        return mapper.selectList(Wrappers.<AiGraphCheckpoint>lambdaQuery()
                .eq(AiGraphCheckpoint::getThreadId, threadId));
    }

    /** 预检：没有答复就挂起。 */
    static class InterruptingNode implements AsyncNodeAction<CpState>, InterruptibleAction<CpState> {
        @Override
        public CompletableFuture<Map<String, Object>> apply(CpState s) {
            return CompletableFuture.completedFuture(Map.of(REPLY, "摘要：" + s.input()));
        }

        @Override
        public Optional<InterruptionMetadata<CpState>> interrupt(String nodeId, CpState state) {
            return state.resumed().isEmpty()
                    ? Optional.of(InterruptionMetadata.builder(nodeId, state).build())
                    : Optional.empty();
        }
    }
}
