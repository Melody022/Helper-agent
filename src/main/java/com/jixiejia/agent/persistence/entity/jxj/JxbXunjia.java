package com.jixiejia.agent.persistence.entity.jxj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新机询价，对应 jxb_xunjia。只读。当前库中 0 行。
 */
@Data
@TableName("jxb_xunjia")
public class JxbXunjia {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ums_member.id */
    private Long customerId;

    /** 称呼 */
    private String name;

    /** 意向机械类型，字典 req_equipment_type */
    private String equipmentType;

    private String phone;

    private Long provinceId;

    private Long cityId;

    private Long districtId;

    /** 审核状态 */
    private String auditStatus;

    private Integer viewNum;

    private String remark;

    /** 删除标记 0 未删除 1 删除 */
    private String delFlag;

    private LocalDateTime createTime;

    private String notes;
}
