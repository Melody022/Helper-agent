package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LangGraph4j 状态图的检查点，对应 ai_graph_checkpoint。
 *
 * <p>存的是"图跑到哪了"（{@code nodeId} / {@code nextNodeId}）加那一轮的图状态。
 * {@code nextNodeId} 有值说明图挂起在那儿等恢复——发布确认的 human-in-the-loop 就靠它。
 *
 * <p>与 {@code ai_publish_request} 的分工：这张表管流程位置，可以随时清掉；
 * 那张表管用户填了什么，是后台审核的依据。本表没有 del_flag。
 */
@Data
@TableName("ai_graph_checkpoint")
public class AiGraphCheckpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话线程 id，本项目用 conversationId */
    private String threadId;

    /** 检查点 id，同一线程内递增 */
    private String checkpointId;

    /** 产出这个检查点的节点 */
    private String nodeId;

    /** 下一步该跑哪个节点；有值说明图挂起在这里 */
    private String nextNodeId;

    /** Base64 的图状态（用图的 StateSerializer 序列化） */
    private String stateData;

    private LocalDateTime createTime;
}
