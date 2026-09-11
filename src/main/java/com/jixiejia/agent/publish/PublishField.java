package com.jixiejia.agent.publish;

/**
 * 发布表单的一个字段。
 *
 * @param name     字段名，作为草稿 payload 的 key
 * @param label    中文名，用于向用户追问和生成确认摘要
 * @param required 是否必填
 * @param kind     字段类型，决定怎么校验
 * @param source   {@link Kind#DICT} 时是字典类型；{@link Kind#CATEGORY} 时是分类层级(1/2/3)；其余为空
 * @param hint     给模型看的抽取提示，比如"如挖掘机、装载机"
 */
public record PublishField(
        String name,
        String label,
        boolean required,
        Kind kind,
        String source,
        String hint
) {

    public enum Kind {
        /** 自由文本，原样落库 */
        TEXT,
        /** 手机号，做格式校验 */
        PHONE,
        /** 字典值。用户可能说中文也可能说码值，落库前统一成码值 */
        DICT,
        /** 地区。把用户说的口语地名解析成 12 位区划码 */
        REGION,
        /** 设备分类。按层级去 sys_category 里找对应 id */
        CATEGORY,
        /** 年份 */
        YEAR
    }

    public static PublishField text(String name, String label, boolean required, String hint) {
        return new PublishField(name, label, required, Kind.TEXT, null, hint);
    }

    public static PublishField phone(String name, String label) {
        return new PublishField(name, label, true, Kind.PHONE, null, "11 位手机号");
    }

    public static PublishField dict(String name, String label, boolean required, String dictType, String hint) {
        return new PublishField(name, label, required, Kind.DICT, dictType, hint);
    }

    public static PublishField region(String name, String label) {
        return new PublishField(name, label, true, Kind.REGION, null, "用户口语里的地名，如洛阳市、河南省");
    }

    public static PublishField category(String name, String label, int grade, String hint) {
        return new PublishField(name, label, true, Kind.CATEGORY, String.valueOf(grade), hint);
    }

    public static PublishField year(String name, String label) {
        return new PublishField(name, label, false, Kind.YEAR, null, "四位年份，如 2018");
    }
}
