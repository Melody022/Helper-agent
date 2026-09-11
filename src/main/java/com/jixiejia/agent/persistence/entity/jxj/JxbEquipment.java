package com.jixiejia.agent.persistence.entity.jxj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备（在售二手/新机），对应 RuoYi 侧 jxb_equipment。
 *
 * <p>只读。平台可见口径见 {@link com.jixiejia.agent.persistence.support.BizFilters#visibleEquipment()}。
 */
@Data
@TableName("jxb_equipment")
public class JxbEquipment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ums_member.id */
    private Long customerId;

    private String name;

    /** 设备编号 */
    private Integer productCode;

    private String subtitle;

    /** 0 新机 1 二手机 2 发车帮 */
    private Integer type;

    /** 销售价（万元） */
    private BigDecimal price;

    /** 卖家售价 */
    private BigDecimal userPrice;

    private String pcDesc;

    private String mobileDesc;

    /** 一级分类 sys_category.id（机型，如"挖掘机"） */
    private Long firstCateId;

    /** 二级分类 sys_category.id（品牌） */
    private Long secondCateId;

    /** 三级分类 sys_category.id（型号系列） */
    private Long thirdCateId;

    private Long typeId;

    /** 品牌 pms_brand.id */
    private Long brandId;

    private String url;

    /** 标签：平台推荐 / 严选好车 / 降价急售 等，逗号或空格分隔 */
    private String tag;

    /** 省 biz_base_region.id（12 位区划码） */
    private Long provinceId;

    /** 市 biz_base_region.id */
    private Long cityId;

    /** 区县 biz_base_region.id */
    private Long districtId;

    /** 设备来源 0 个人发布 1 平台车源 2 自营认证 */
    private String source;

    private String modifyName;

    /** 表显小时数 */
    private Integer usedHours;

    /** 出厂年限（四位年份） */
    private Integer factoryDate;

    private LocalDateTime publishTime;

    /** 审核状态 0 通过 1 未通过 2 审核中 */
    private String status;

    /** 上架状态 0 下架 1 上架 2 违规下架 */
    private String shelvesStatus;

    /** 删除标记 0 未删除 1 删除；历史行可能为 NULL */
    private Integer delFlag;

    private String createName;

    private String delName;

    private LocalDateTime createTime;

    private LocalDateTime modifyTime;

    private LocalDateTime delTime;

    private BigDecimal commissionRate;

    private BigDecimal sCommissionRate;

    /** 是否虚拟商品 0 否 1 是 */
    private String isVirtual;

    private String video;

    private String videoPic;

    private Long storeId;

    private String phone;

    private String remark;

    private String notes;
}
