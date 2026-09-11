package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-工具白名单，对应 ai_role_tool。这是工具权限的唯一判据：
 * 某个角色能用哪些工具，就查这张表。
 */
@Data
@TableName("ai_role_tool")
public class AiRoleTool {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roleId;

    private Long toolId;

    private LocalDateTime createTime;
}
