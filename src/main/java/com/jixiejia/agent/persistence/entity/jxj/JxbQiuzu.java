package com.jixiejia.agent.persistence.entity.jxj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 求租（机主发起的租机需求），对应 jxb_qiuzu。只读。
 *
 * <p>equipmentType / projectType / trailerFee / payType / status 均为字典码，
 * 对应字典类型见各字段注释。
 */
@Data
@TableName("jxb_qiuzu")
public class JxbQiuzu {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ums_member.id */
    private Long customerId;

    /** 设备类型，字典 zl_sblx：wjj 挖掘机 / xwz 旋挖钻 / ttj 推土机 ... */
    private String equipmentType;

    /** 品牌型号，自由文本 */
    private String equipmentModel;

    /** 工程类型，字典 qz_gclx：cqgc 拆迁工程 / qsgc 矿山工程 ... */
    private String projectType;

    /** 进场时间 */
    private LocalDateTime startDate;

    /** 工期，自由文本如 "5天" */
    private String duration;

    /** 拖车费，字典 jxb_tcf：dt 单趟 / lh 来回 */
    private String trailerFee;

    /** 付款方式，字典 qz_fkfs：xk 现款 / gcqfk 工程期付款 / xsfk 协商付款 */
    private String payType;

    /** 状态，字典 qz_status：jxz 进行中 / yjs 已结束 */
    private String status;

    private String phone;

    private Integer viewNum;

    private Long provinceId;

    private Long cityId;

    private Long districtId;

    /** 详细信息 */
    private String remark;

    /** 审核状态，字典 audit_thress */
    private String auditStatus;

    /** 删除标记 0 未删除 1 删除；活动行历史遗留为 NULL 或空串 */
    private String delFlag;

    private LocalDateTime createTime;

    /** 租金，自由文本 */
    private String rent;
}
