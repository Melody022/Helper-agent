package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 账号与平台会员的映射，对应 ai_publish_mapping。
 *
 * <p>发布落库时 {@code customer_id} 必须填平台会员 id（{@code ums_member.id}），
 * 而不是 AI 账号 id——两张表的 id 各自独立，直接拿 AI 账号 id 填进去，
 * 在平台上会指向一个不相干的会员，等于把设备挂到别人名下。
 * 所以发布前必须先有这条映射。
 */
@Data
@TableName("ai_publish_mapping")
public class AiPublishMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ai_user.id */
    private Long userId;

    /** ums_member.id */
    private Long memberId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
