package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 侧角色，对应 ai_role。种子数据见 V1 脚本：ADMIN / USER。
 */
@Data
@TableName("ai_role")
public class AiRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色标识 ADMIN / USER */
    private String roleKey;

    private String roleName;

    private Integer sort;

    /** 0 正常 1 停用 */
    private String status;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String remark;
}
