package com.jixiejia.agent.publish;

import java.util.List;

/**
 * 可以发布的两类信息，以及各自需要收集哪些字段。
 *
 * <p>表单是<b>代码里写死的</b>，不是让模型自由发挥。原因很直接：发布是写操作，
 * 字段必须和目标表的列一一对应，模型编一个字段名出来就只能落一半数据。
 * 模型在这里的职责只有一个——从用户的话里把值抽出来，填进这张表。
 */
public enum PublishTarget {

    /** 出租：把设备挂出去 */
    CHUZU("jxb_chuzu", "出租", List.of(
            PublishField.category("machineType", "设备机型", 1, "如挖掘机、装载机、旋挖钻"),
            PublishField.dict("tonnage", "吨位", true, "jxb_jxdw", "如 6-9吨、20-29吨"),
            PublishField.region("area", "所在地区"),
            PublishField.text("rent", "租金", true, "如 1000/天 不带油"),
            PublishField.dict("ownerType", "出租方", true, "jxb_sbss", "个人 或 公司"),
            PublishField.phone("phone", "联系电话"),
            PublishField.year("factoryYear", "出厂年限"),
            PublishField.text("detailAddress", "详细地址", false, "如 洛龙区某工地"),
            PublishField.text("remark", "设备说明", false, "车况、配件、有无手续等"))),

    /** 求租：机主要找活 / 用工方要找机器 */
    QIUZU("jxb_qiuzu", "求租", List.of(
            PublishField.dict("equipmentType", "设备类型", true, "zl_sblx", "如挖掘机、装载机、旋挖钻"),
            PublishField.dict("projectType", "工程类型", true, "qz_gclx", "如拆迁工程、矿山工程、市政工程"),
            PublishField.text("duration", "工期", true, "如 5天、一个月"),
            PublishField.dict("payType", "付款方式", true, "qz_fkfs", "现款 / 工程期付款 / 协商付款"),
            PublishField.region("area", "所在地区"),
            PublishField.phone("phone", "联系电话"),
            PublishField.dict("trailerFee", "拖车费", false, "jxb_tcf", "单趟 或 来回"),
            PublishField.text("rent", "租金", false, "如 1000/天"),
            PublishField.text("remark", "详细说明", false, "其他补充信息")));

    private final String table;
    private final String label;
    private final List<PublishField> fields;

    PublishTarget(String table, String label, List<PublishField> fields) {
        this.table = table;
        this.label = label;
        this.fields = fields;
    }

    /** 目标表名 */
    public String table() {
        return table;
    }

    /** 中文名，用于话术 */
    public String label() {
        return label;
    }

    public List<PublishField> fields() {
        return fields;
    }

    public List<PublishField> requiredFields() {
        return fields.stream().filter(PublishField::required).toList();
    }

    /** 按目标表名反查。落库时根据草稿里记的表名还原类型。 */
    public static PublishTarget byTable(String table) {
        for (PublishTarget t : values()) {
            if (t.table.equals(table)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的发布目标表：" + table);
    }

    /** 按名称找字段。 */
    public PublishField field(String name) {
        return fields.stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        label + " 表单里没有字段：" + name));
    }
}
