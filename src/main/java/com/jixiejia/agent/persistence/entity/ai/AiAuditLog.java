package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 路由链审计，对应 ai_audit_log。每次对话都把"命中第几步、意图、分类层、Agent、工具、
 * 证据分"落一条，用于回答"这条消息为什么被这么处理"。本表没有 del_flag。
 */
@Data
@TableName("ai_audit_log")
public class AiAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    private Long userId;

    /** 命中的路由步骤，取值见 {@link com.jixiejia.agent.router.RouteStage} */
    private String routeStage;

    private String intent;

    private BigDecimal confidence;

    /** 分类层 keyword / small / llm */
    private String classifyLayer;

    private String agentKey;

    /** 调用的工具，逗号分隔 */
    private String toolNames;

    private BigDecimal evidenceScore;

    private Integer evidenceCount;

    /** 分流决策明细，JSON */
    private String decision;

    private Integer latencyMs;

    /** 0 否 1 是 */
    private Integer success;

    private String errorMsg;

    private LocalDateTime createTime;
}
