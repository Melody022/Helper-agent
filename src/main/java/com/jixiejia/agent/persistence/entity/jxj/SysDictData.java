package com.jixiejia.agent.persistence.entity.jxj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字典数据，对应 RuoYi 的 sys_dict_data。只读。
 *
 * <p>业务表里 tonnage / status / payType 这类字段落库的是 {@code dictValue}（如 "dz"），
 * 需要经 {@link com.jixiejia.agent.persistence.support.DictService} 换成 {@code dictLabel}（"待租"）。
 */
@Data
@TableName("sys_dict_data")
public class SysDictData {

    @TableId(value = "dict_code", type = IdType.AUTO)
    private Long dictCode;

    private Integer dictSort;

    /** 展示文案，如 "待租" */
    private String dictLabel;

    /** 落库值，如 "dz" */
    private String dictValue;

    /** 字典类型，如 "cz_sbzt" */
    private String dictType;

    private String cssClass;

    private String listClass;

    /** 是否默认 Y/N */
    private String isDefault;

    /** 状态 0 正常 1 停用 */
    private String status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
