package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发布请求，对应 ai_publish_request。承载"填表 → 确认 → 落库"整个流程的状态。
 *
 * <p>状态流转：
 * <pre>
 *   DRAFT（收集中，confirm_token 为空）
 *     → DRAFT（字段齐了，已发确认令牌，等用户确认）
 *     → SUBMITTED（用户确认，已写入业务表待审核）
 *     → EXPIRED（令牌超时未确认）
 * </pre>
 *
 * <p>为什么用数据库记状态而不是放在内存里：确认动作发生在<b>另一个请求</b>里
 * （用户看完摘要再回复），中间可能隔几分钟、也可能换了设备。
 * 状态放内存里，进程一重启就没了。
 */
@Data
@TableName("ai_publish_request")
public class AiPublishRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务流水号，对外用它标识这条发布请求 */
    private String requestNo;

    private String conversationId;

    /** ai_user.id */
    private Long userId;

    /** ums_member.id，落库时作为 customer_id */
    private Long memberId;

    /** 目标表 jxb_chuzu / jxb_qiuzu */
    private String targetTable;

    /** 表单数据，JSON */
    private String payload;

    /** DRAFT / CONFIRMED / SUBMITTED / REJECTED / EXPIRED */
    private String status;

    /** 确认令牌，一次性 */
    private String confirmToken;

    private LocalDateTime tokenExpireTime;

    /** 落库后的目标表与主键 */
    private String bizTable;

    private Long bizId;

    private Long reviewerId;

    private String reviewNote;

    private LocalDateTime reviewTime;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
