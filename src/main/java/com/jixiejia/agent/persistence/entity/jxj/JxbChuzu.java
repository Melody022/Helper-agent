package com.jixiejia.agent.persistence.entity.jxj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出租（设备招租），对应 jxb_chuzu。只读。
 *
 * <p>字段里 tonnage / ownerType / status / auditStatus 存的都是字典码，
 * 展示前需经 {@link com.jixiejia.agent.persistence.support.DictService} 转成中文。
 */
@Data
@TableName("jxb_chuzu")
public class JxbChuzu {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ums_member.id */
    private Long customerId;

    /** 设备类型 sys_category.id */
    private Long firstCateId;

    /** 设备品牌 sys_category.id */
    private Long secondCateId;

    /** 设备型号 sys_category.id */
    private Long thirdCateId;

    private String equipmentModel;

    private String url;

    /** 设备图片，多张以逗号分隔 */
    private String images;

    /** 吨位，字典 jxb_jxdw：0-6 / 6-9 / 10-19 / 20-29 / 30-49 / 50-100 */
    private String tonnage;

    /** 个人/公司，字典 jxb_sbss：gr 个人 / gs 公司 */
    private String ownerType;

    /** 状态，字典 cz_sbzt：dz 待租 / ml 忙碌 */
    private String status;

    /** 租金，自由文本，如 "1000/天 不带油" */
    private String rent;

    private String phone;

    private Integer viewNum;

    private Long provinceId;

    private Long cityId;

    private Long districtId;

    private String detailAddress;

    /** 审核状态，字典 audit_thress：0 申请中 / 1 通过 / 2 拒绝 */
    private String auditStatus;

    /** 设备说明 */
    private String remark;

    /** 删除标记 0 未删除 1 删除；活动行历史遗留为 NULL 或空串 */
    private String delFlag;

    private LocalDateTime createTime;

    /** 出厂年限 */
    private Integer factoryDate;
}
