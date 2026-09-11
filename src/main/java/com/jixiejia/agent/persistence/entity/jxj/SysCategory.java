package com.jixiejia.agent.persistence.entity.jxj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 设备分类，对应 sys_category。只读。
 *
 * <p>按 {@code grade} 分三级，且三级的语义各不相同：
 * <ul>
 *   <li>grade=1 机型，如"挖掘机""装载机"（parent_id=0）</li>
 *   <li>grade=2 品牌，如"日立""小松"（挂在机型下）</li>
 *   <li>grade=3 型号系列，如"ZX08系列""PC360-7"</li>
 * </ul>
 * jxb_equipment / jxb_chuzu 的 first/second/third_cate_id 分别对应这三级的 id。
 * 注意库内 grade=1 存在同名重复行（如两条"附件工装"）和废弃行（del_flag=1）。
 */
@Data
@TableName("sys_category")
public class SysCategory {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    /** 父级分类 id，一级为 0 */
    private Long parentId;

    private Long typeId;

    /** 层级 1 机型 2 品牌 3 型号 */
    private Integer grade;

    private Integer sort;

    /** 删除标记 0 未删除 1 删除 */
    private String delFlag;

    private Long brandId;
}
