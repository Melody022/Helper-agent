package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据飞轮待审候选，对应 ai_flywheel_candidate。
 *
 * <p>三个入口往里写：意图置信度过低、证据闸判定资料不足、回答自评不过。
 * 进来的问题先做标准化与查重，同一条问题只留一条候选，
 * 再由人工审核决定是否补进知识库——补进去之后下次就能答上。
 */
@Data
@TableName("ai_flywheel_candidate")
public class AiFlywheelCandidate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 入口：LOW_CONFIDENCE / SELF_EVAL_FAIL / USER_UNRESOLVED / WEAK_EVIDENCE */
    private String source;

    private String conversationId;

    private Long messageId;

    /** 用户原始问题 */
    private String question;

    /** 标准化后的问题（去标点、去语气词），查重用 */
    private String standardQuestion;

    /** 问题指纹（标准化后取哈希），查重键 */
    private String questionHash;

    /** 当时的回答 */
    private String answer;

    /** 置信度 / 自评分 / 检索分 */
    private BigDecimal score;

    /** PENDING / APPROVED / REJECTED / MERGED */
    private String status;

    private Long reviewerId;

    private String reviewNote;

    private LocalDateTime reviewTime;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
