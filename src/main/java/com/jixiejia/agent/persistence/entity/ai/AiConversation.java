package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话，对应 ai_conversation。conversation_id 同时是 LangGraph4j 的 threadId。
 *
 * <p>last_agent_key / last_intent 是会话粘性的落库副本，运行时判据以 Redis 为准
 * （见 {@link com.jixiejia.agent.router.StickySessionStore}）；这里存一份是为了管理台能直接看。
 */
@Data
@TableName("ai_conversation")
public class AiConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话标识，即 LangGraph4j threadId */
    private String conversationId;

    private Long userId;

    /** ums_member.id */
    private Long memberId;

    private String title;

    /** 最近命中的 Agent（会话粘性） */
    private String lastAgentKey;

    private String lastIntent;

    /** ACTIVE / CLOSED / HANDOFF */
    private String status;

    private Integer messageCount;

    private LocalDateTime lastActiveTime;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
