package com.jixiejia.agent.publish;

import com.jixiejia.agent.persistence.support.CategoryService;
import com.jixiejia.agent.persistence.support.DictService;
import com.jixiejia.agent.persistence.support.RegionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 校验并归一化表单字段值。
 *
 * <p>用户说的是"洛阳""6-9吨""个人"这种口语，而目标表里存的是区划码、字典码、分类 id。
 * 这一步负责把前者变成后者——<b>转不过去就当作没填</b>，绝不会把"洛阳"这五个字
 * 直接塞进 province_id 列（那列是 bigint，塞进去要么报错、要么变成 0）。
 *
 * <p>归一化失败时返回错误说明而不是抛异常：要能让用户知道哪个值没听懂，
 * 而不是整个流程崩掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishValueValidator {

    /** 中国大陆手机号 */
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    /** 出厂年份的合理区间。留出余量：太老的机械确实存在，但不能是 11111 */
    private static final int MIN_YEAR = 1950;

    private final DictService dictService;
    private final RegionService regionService;
    private final CategoryService categoryService;

    /**
     * 一个字段的校验结果。
     *
     * @param value   归一化后要落库的值（区划是嵌套 Map），校验失败为 null
     * @param display 给用户看的展示值
     * @param error   失败原因，成功为 null
     */
    public record Outcome(Object value, String display, String error) {

        public boolean ok() {
            return error == null;
        }

        static Outcome of(Object value, String display) {
            return new Outcome(value, display, null);
        }

        static Outcome fail(String error) {
            return new Outcome(null, null, error);
        }
    }

    /** 按字段类型校验并归一化。 */
    public Outcome validate(PublishField field, String raw) {
        if (raw == null || raw.isBlank()) {
            return Outcome.fail(field.label() + "不能为空");
        }
        String value = raw.trim();

        return switch (field.kind()) {
            case TEXT -> Outcome.of(value, value);
            case PHONE -> validatePhone(field, value);
            case YEAR -> validateYear(field, value);
            case DICT -> validateDict(field, value);
            case CATEGORY -> validateCategory(field, value);
            case REGION -> validateRegion(field, value);
        };
    }

    private Outcome validatePhone(PublishField field, String value) {
        // 用户可能写成 "138-1133-4488" 或带空格，先去掉分隔符再校验
        String digits = value.replaceAll("[\\s\\-]", "");
        if (!PHONE.matcher(digits).matches()) {
            return Outcome.fail(field.label() + "看起来不是有效的手机号，请提供 11 位号码");
        }
        return Outcome.of(digits, digits);
    }

    private Outcome validateYear(PublishField field, String value) {
        java.util.regex.Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(value);
        if (!m.find()) {
            return Outcome.fail(field.label() + "没听懂，请给一个四位年份，比如 2018");
        }
        int year = Integer.parseInt(m.group());
        int current = Year.now().getValue();
        if (year < MIN_YEAR || year > current + 1) {
            return Outcome.fail(field.label() + "不太对：" + year + " 年不在合理范围（" + MIN_YEAR + "-" + current + "）");
        }
        return Outcome.of(year, String.valueOf(year));
    }

    private Outcome validateDict(PublishField field, String value) {
        String code = dictService.valueOf(field.source(), value);
        if (code == null) {
            // 用户也可能直接给了码值
            code = dictService.label(field.source(), value) != null ? value : null;
        }
        if (code == null) {
            return Outcome.fail(field.label() + "「" + value + "」不在可选范围里，可选："
                    + String.join("、", dictService.options(field.source()).values()));
        }
        return Outcome.of(code, dictService.labelOrRaw(field.source(), code));
    }

    private Outcome validateCategory(PublishField field, String value) {
        int grade = Integer.parseInt(field.source());
        Long id = grade == CategoryService.GRADE_MACHINE
                ? categoryService.findMachineId(value)
                : categoryService.findBrandId(value);
        if (id == null) {
            return Outcome.fail(field.label() + "「" + value + "」在平台分类里找不到");
        }
        return Outcome.of(id, categoryService.name(id));
    }

    /**
     * 地区是唯一一个"一个字段对应多个列"的：用户说"洛阳"，要落成
     * province_id / city_id / district_id 三个值。
     *
     * <p>这三个值只要有一个能推出来，其余的都从区划链里补全——
     * 平台的数据是三级都填的。只填了 city_id 的话，这条信息在按省筛选时查不到，
     * 用户会觉得"发了却没出现"，还很难查到原因。
     */
    private Outcome validateRegion(PublishField field, String value) {
        // 用户可能说省、说市、也可能说区县，依次尝试；命中哪一级都行
        Long anchor = regionService.resolveProvinceId(value);
        if (anchor == null) {
            anchor = regionService.resolveCityId(value);
        }
        if (anchor == null) {
            anchor = regionService.resolveId(value, RegionService.LEVEL_DISTRICT);
        }
        if (anchor == null) {
            return Outcome.fail("没能识别地区「" + value + "」，换个说法试试，比如直接说城市名");
        }

        // 从锚点补齐三级：说不出省就顺着区划链往上找
        Long provinceId = regionService.provinceIdOf(anchor);
        Long cityId = regionService.cityIdOf(anchor);

        // 锚点既不是省也不是市，那它就是区县。
        // 必须两个上级都解析出来了才敢这么判断，否则会把市误当成区县
        Long districtId = (provinceId != null && cityId != null
                && !anchor.equals(provinceId) && !anchor.equals(cityId)) ? anchor : null;

        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("provinceId", provinceId);
        stored.put("cityId", cityId);
        stored.put("districtId", districtId);

        String display = regionService.fullName(provinceId, cityId, districtId);
        return Outcome.of(stored, display == null ? value : display);
    }

    /**
     * 地区值存的是嵌套 Map，取值时统一从这里读，避免各处判空逻辑不一致。
     */
    @SuppressWarnings("unchecked")
    public static Long regionPart(Object regionValue, String part) {
        if (regionValue instanceof Map<?, ?> map) {
            Object v = map.get(part);
            if (v instanceof Number n) {
                return n.longValue();
            }
        }
        return null;
    }
}
