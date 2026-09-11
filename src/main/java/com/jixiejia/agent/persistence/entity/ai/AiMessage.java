package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 会话消息，对应 ai_message。本表没有 del_flag（消息只追加不删除）。
 */
@Data
@TableName("ai_message")
public class AiMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    /** user / assistant / system / tool */
    private String role;

    private String content;

    /** 识别出的意图 */
    private String intent;

    private BigDecimal confidence;

    private String agentKey;

    /** 工具调用记录，JSON */
    private String toolCalls;

    private Integer tokenInput;

    private Integer tokenOutput;

    private Integer latencyMs;

    private LocalDateTime createTime;
}
