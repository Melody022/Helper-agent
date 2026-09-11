package com.jixiejia.agent.persistence.entity.jxj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 省市区，对应 biz_base_region。只读。
 *
 * <p>库内另有 region / ls_province / ls_city / ls_district 等同义表，都是干扰项，
 * 一律不要用：只有本表的 id 就是 12 位行政区划码，与 jxb_*.province_id / city_id /
 * district_id 直接相等，可以 join。
 *
 * <p>{@code level}：1 省 / 2 市 / 3 区县。直辖市会出现省市同名（北京市 / 北京市）。
 */
@Data
@TableName("biz_base_region")
public class BizBaseRegion {

    /** 地区 ID，沿用 12 位行政区划码，如 410300000000 = 洛阳市 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 父级 ID：省级为 NULL，市级指向省，区县指向市 */
    private Long parentId;

    private String name;

    /** 层级 1 省 2 市 3 区县 */
    private Integer level;

    private Integer sort;

    /** 删除标记 0 正常 1 删除 */
    private String delFlag;

    /** 经度 */
    private BigDecimal lng;

    /** 纬度 */
    private BigDecimal lat;

    public boolean isProvince() {
        return level != null && level == 1;
    }

    public boolean isCity() {
        return level != null && level == 2;
    }

    public boolean isDistrict() {
        return level != null && level == 3;
    }
}
