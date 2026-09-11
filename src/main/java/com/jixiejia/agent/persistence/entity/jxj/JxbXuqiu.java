package com.jixiejia.agent.persistence.entity.jxj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用机需求（用工方发布的用机需求），对应 jxb_xuqiu。只读。
 *
 * <p>注意：本表没有 province_id / city_id，位置只有自由文本 address + 经纬度，
 * 做地区筛选时不能像 jxb_chuzu 那样关联 biz_base_region。
 */
@Data
@TableName("jxb_xuqiu")
public class JxbXuqiu {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ums_member.id */
    private Long customerId;

    /** 姓名 / 公司 */
    private String name;

    private String contact;

    /** 设备类型，字典 req_equipment_type：dxwjj 大挖掘机 / zzj 装载机 ... */
    private String reqType;

    /** 需求数量 */
    private Integer reqNum;

    /** 其他备注 */
    private String reqRemark;

    /** 施工工期 */
    private String duration;

    /** 施工地址（自由文本） */
    private String address;

    private BigDecimal addressLongitude;

    private BigDecimal addressLatitude;

    /** 结款方式，字典 qz_fkfs */
    private String payType;

    /** 结款方，字典 jxb_sbss：gr 个人 / gs 公司 */
    private String ownerType;

    private String status;

    private String phone;

    private Integer viewNum;

    /** 详细信息 */
    private String remark;

    /** 审核状态，字典 audit_thress */
    private String auditStatus;

    /** 删除标记 0 未删除 1 删除 */
    private String delFlag;

    private LocalDateTime createTime;

    private String notes;
}
