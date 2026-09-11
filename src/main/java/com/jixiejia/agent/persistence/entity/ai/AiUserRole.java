package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号-角色关联，对应 ai_user_role。
 *
 * <p>一个账号可以有多条记录（多角色）。管理台能力与工具白名单都以此为判据，
 * 是"管理员和普通用户分开"的落点。
 */
@Data
@TableName("ai_user_role")
public class AiUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roleId;

    private LocalDateTime createTime;
}
