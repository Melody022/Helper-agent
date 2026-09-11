package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 侧账号，对应 ai_user。与平台会员（ums_member）的对应关系在 ai_publish_mapping。
 */
@Data
@TableName("ai_user")
public class AiUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希 */
    private String password;

    private String nickname;

    /** 0 正常 1 停用 */
    private String status;

    private String mobile;

    private String email;

    private LocalDateTime lastLoginTime;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String remark;
}
