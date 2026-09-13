package com.jixiejia.agent.graph;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiGraphCheckpoint;
import com.jixiejia.agent.persistence.mapper.ai.AiGraphCheckpointMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 把图检查点存进 MySQL 的 {@code ai_graph_checkpoint}。
 *
 * <p><b>为什么自己写一个。</b>LangGraph4j 核心包只自带 {@code MemorySaver}（进程一重启就没了）
 * 和 {@code FileSystemSaver}（写本地磁盘）。前者和项目已经写明的原则冲突——
 * {@code PublishFormService} 的类注释里就写着"状态放内存里，进程一重启就没了，
 * 用户会莫名其妙地'从头再来'"；后者会把图状态混进知识库原件那个落盘目录，不好排查。
 * 库里没有 MySQL 实现，而 {@link AbstractCheckpointSaver} 只有 4 个抽象方法，自己实现更干净。
 *
 * <p><b>序列化用的是图自己的 {@link StateSerializer}，没有另起一套。</b>
 * 这样"状态里能放什么"由图的通道定义统一决定，不会出现
 * "图能跑但存不进库"这种只在挂起时才暴露的问题。
 * 代价是状态的每个值都得可序列化——与开 checkpointer 的图，state 里只放 String / Long /
 * Boolean 这类 JDK 类型（见 {@code PublishState} 的类注释）。
 *
 * <p><b>用 {@code ObjectInputStream} 反序列化自己库里的数据</b>：数据来源是本进程写入的，
 * 不经用户输入，接受这个风险；若哪天这张表能被外部写入，这里要换成白名单式的序列化。
 */
@Slf4j
public class MysqlCheckpointSaver extends AbstractCheckpointSaver {

    private final AiGraphCheckpointMapper mapper;
    private final StateSerializer<? extends AgentState> serializer;

    public MysqlCheckpointSaver(AiGraphCheckpointMapper mapper,
                                StateSerializer<? extends AgentState> serializer) {
        this.mapper = mapper;
        this.serializer = serializer;
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
        List<AiGraphCheckpoint> rows = mapper.selectList(
                Wrappers.<AiGraphCheckpoint>lambdaQuery()
                        .eq(AiGraphCheckpoint::getThreadId, threadId(config))
                        .orderByAsc(AiGraphCheckpoint::getId));

        LinkedList<Checkpoint> out = new LinkedList<>();
        for (AiGraphCheckpoint row : rows) {
            try {
                out.add(toCheckpoint(row));
            } catch (Exception e) {
                // 单条坏数据不该让整条线程失忆：跳过它，至少要能读到最后一条好的
                log.warn("检查点反序列化失败，跳过：thread={} checkpoint={}",
                        row.getThreadId(), row.getCheckpointId(), e);
            }
        }
        return out;
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                      Checkpoint checkpoint) throws IOException {
        AiGraphCheckpoint row = new AiGraphCheckpoint();
        row.setThreadId(threadId(config));
        row.setCheckpointId(checkpoint.getId());
        row.setNodeId(checkpoint.getNodeId());
        row.setNextNodeId(checkpoint.getNextNodeId());
        row.setStateData(encode(checkpoint.getState()));
        mapper.insert(row);
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                     Checkpoint checkpoint) throws IOException {
        // 必须显式 set：MyBatis-Plus 的 updateById 会跳过 null 字段，
        // 那样"把 next_node_id 清空"（图跑完、不再挂起）永远不生效——项目里踩过同类坑
        mapper.update(null, Wrappers.<AiGraphCheckpoint>lambdaUpdate()
                .eq(AiGraphCheckpoint::getThreadId, threadId(config))
                .eq(AiGraphCheckpoint::getCheckpointId, checkpoint.getId())
                .set(AiGraphCheckpoint::getNodeId, checkpoint.getNodeId())
                .set(AiGraphCheckpoint::getNextNodeId, checkpoint.getNextNodeId())
                .set(AiGraphCheckpoint::getStateData, encode(checkpoint.getState())));
    }

    @Override
    protected Tag releaseCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints) {
        String threadId = threadId(config);
        mapper.delete(Wrappers.<AiGraphCheckpoint>lambdaQuery()
                .eq(AiGraphCheckpoint::getThreadId, threadId));
        return new Tag(threadId, checkpoints);
    }

    /** 把某条线程的检查点全清掉——发布流程走完之后收尾用，避免这张表无限长。 */
    public void clear(String threadId) {
        mapper.delete(Wrappers.<AiGraphCheckpoint>lambdaQuery()
                .eq(AiGraphCheckpoint::getThreadId, threadId));
    }

    /**
     * 这条线程是不是正挂在某个节点上等恢复。
     *
     * <p>判据是最后一条检查点的 {@code next_node_id} 是不是<b>一个真实节点</b>。
     *
     * <p>⚠️ 这里踩过一次：最初只判了"非空"，结果**图正常跑完时 {@code next_node_id}
     * 是 {@code __END__} 而不是 null**，于是每一轮都被当成"还挂着"，
     * 下一轮全走了恢复分支——流程整个错位，而且表现为"模型被反复调用、答非所问"，
     * 完全看不出是这里的问题。所以必须把 {@code __END__}（和 {@code __START__}）排除掉。
     */
    public boolean isSuspended(String threadId) {
        AiGraphCheckpoint last = mapper.selectOne(Wrappers.<AiGraphCheckpoint>lambdaQuery()
                .eq(AiGraphCheckpoint::getThreadId, threadId)
                .orderByDesc(AiGraphCheckpoint::getId)
                .last("limit 1"));
        if (last == null) {
            return false;
        }
        String next = last.getNextNodeId();
        return next != null && !next.isBlank()
                && !next.equals(END_MARKER) && !next.equals(START_MARKER);
    }

    /** 库里的哨兵节点名，不是真实节点。 */
    private static final String END_MARKER = "__END__";
    private static final String START_MARKER = "__START__";

    private Checkpoint toCheckpoint(AiGraphCheckpoint row) throws Exception {
        return Checkpoint.builder()
                .id(row.getCheckpointId())
                .state(decode(row.getStateData()))
                .nodeId(row.getNodeId())
                .nextNodeId(row.getNextNodeId())
                .build();
    }

    private String encode(Map<String, Object> state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            serializer.writeData(state, out);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private Map<String, Object> decode(String data) throws IOException, ClassNotFoundException {
        if (data == null || data.isBlank()) {
            return Map.of();
        }
        byte[] bytes = Base64.getDecoder().decode(data);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return serializer.readData(in);
        }
    }
}
