package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jixiejia.agent.persistence.support.CategoryService;
import com.jixiejia.agent.persistence.support.DictService;
import com.jixiejia.agent.persistence.support.RegionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 查询工具的公共支撑：结果封装、地区解析、字典/分类翻译、条数限制。
 *
 * <p>几条贯穿所有工具的原则：
 * <ul>
 *   <li><b>返回值一律是 JSON 字符串</b>。Spring AI 对 String 返回值原样透传，
 *       不用赌它内部的对象转 JSON 行为是否稳定。</li>
 *   <li><b>出参已全部中文化</b>。字典码、区划码、分类 id 都在这里翻成中文再交给模型，
 *       否则模型看到 {@code dz} / {@code 110100000000} 只能编。</li>
 *   <li><b>必须限量</b>。默认 5 条、上限 20 条，防止把整库塞进上下文。</li>
 *   <li><b>口头地名要回显</b>。用户说"洛阳"，结果里带上"洛阳市"，
 *       模型和用户都能立刻发现听错了地方。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolSupport {

    /** 默认返回条数 */
    public static final int DEFAULT_LIMIT = 5;

    /** 单次返回上限，避免把整库灌进上下文 */
    public static final int MAX_LIMIT = 20;

    /** 列表类工具的统一返回体。 */
    public record ListResult<T>(int total, List<T> items, String note) {
    }

    /** 详情类工具的统一返回体。 */
    public record ItemResult<T>(T item, String note) {
    }

    /** 地区解析结果：level 1 省 / 2 市 / 3 区县 */
    public record AreaMatch(Long id, int level, String name) {
    }

    private final RegionService regionService;
    private final DictService dictService;
    private final CategoryService categoryService;

    /**
     * 工具出参专用的 Jackson 3 序列化器，自己构建而不注入容器里的那个。
     *
     * <p>两个原因：一是工具出参格式要稳定可预期（ISO 时间、省略 null 字段），
     * 不该随 Web 层的 Jackson 配置变动；二是自己构建就不依赖容器里到底注册了哪个 ObjectMapper Bean。
     * Jackson 3 已内置 java.time 支持，无需额外注册 JavaTimeModule。
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    /** 把结果对象序列化成 JSON 字符串。序列化失败时降级为错误 JSON，绝不抛异常打断对话。 */
    public String json(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            // Jackson 3 的 JacksonException 是非受检异常，工具边界必须兜住：
            // 一次序列化失败不该让整轮对话崩掉。
            log.warn("工具返回结果序列化失败", e);
            return "{\"error\":\"结果序列化失败\"}";
        }
    }

    /** 条数兜底：null → 默认 5，超过上限截到 20。 */
    public int capLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 空串/空白 → null，避免把空字符串当条件拼进 SQL。 */
    public String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * 把用户口语地名解析成区划。按 省 → 市 → 区县 顺序尝试，命中即返回。
     *
     * <p>顺序有讲究："北京"既是省也是市，先按省命中，过滤 province_id 能覆盖全市数据；
     * 反过来只按市命中，"北京市朝阳区"这类更细的说法就落不进来。
     *
     * @return 解析不到时返回 null，调用方应在结果里加提示而不是静默忽略
     */
    public AreaMatch matchArea(String area) {
        String a = blankToNull(area);
        if (a == null) {
            return null;
        }
        Long provinceId = regionService.resolveProvinceId(a);
        if (provinceId != null) {
            return new AreaMatch(provinceId, RegionService.LEVEL_PROVINCE, regionService.name(provinceId));
        }
        Long cityId = regionService.resolveCityId(a);
        if (cityId != null) {
            return new AreaMatch(cityId, RegionService.LEVEL_CITY, regionService.name(cityId));
        }
        Long districtId = regionService.resolveId(a, RegionService.LEVEL_DISTRICT);
        if (districtId != null) {
            return new AreaMatch(districtId, RegionService.LEVEL_DISTRICT, regionService.name(districtId));
        }
        return null;
    }

    /**
     * 把地区条件套到查询上。三列任一命中即可，这样省/市/区县三种粒度共用一套过滤。
     * match 为 null 时不加条件（调用方负责给出提示）。
     */
    public static <T> void applyArea(LambdaQueryWrapper<T> wrapper, AreaMatch match,
                                     SFunction<T, ?> provinceColumn,
                                     SFunction<T, ?> cityColumn,
                                     SFunction<T, ?> districtColumn) {
        if (match == null) {
            return;
        }
        switch (match.level()) {
            case RegionService.LEVEL_PROVINCE -> wrapper.eq(provinceColumn, match.id());
            case RegionService.LEVEL_CITY -> wrapper.eq(cityColumn, match.id());
            case RegionService.LEVEL_DISTRICT -> wrapper.eq(districtColumn, match.id());
            default -> {
                // 其它层级不加条件
            }
        }
    }

    /** 字典翻译，查不到时返回原名。 */
    public String dict(String dictType, String value) {
        return dictService.labelOrRaw(dictType, value);
    }

    /** 字典翻译，查不到返回 null（用于可选字段，避免把 null 展示成 "null"）。 */
    public String dictOrNull(String dictType, String value) {
        return dictService.label(dictType, value);
    }

    /**
     * 把模型填的字典参数归一成库里的码值。模型可能填中文（"待租"）也可能填码值（"dz"），
     * 两种都接受；都认不出来时返回 null，由调用方当作"该条件未指定"处理。
     */
    public String normalizeDict(String dictType, String input) {
        String v = blankToNull(input);
        if (v == null) {
            return null;
        }
        if (dictService.label(dictType, v) != null) {
            return v;
        }
        return dictService.valueOf(dictType, v);
    }

    /** 字典的合法取值说明，用于拼进"未识别"提示里。 */
    public String dictHint(String dictType) {
        return String.join("/", dictService.options(dictType).values());
    }

    /** 分类 id → 名称。 */
    public String category(Long categoryId) {
        return categoryService.name(categoryId);
    }

    /**
     * 解析设备标签。jxb_equipment.tag 里混着两种东西：
     * 一是 product_tag 字典码（pttj/yxhc/jjjs/tjjl），二是"陆弘机械,36吨"这类自由文本。
     * 两种都要保留，只是码值要翻成中文。
     */
    public List<String> parseTags(String tag) {
        List<String> result = new ArrayList<>();
        String t = blankToNull(tag);
        if (t == null) {
            return result;
        }
        for (String part : t.split("[,，]")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String label = dictOrNull("product_tag", p);
            result.add(label != null ? label : p);
        }
        return result;
    }

    /** 区划码 → 中文，批量。 */
    public String location(Long provinceId, Long cityId, Long districtId) {
        return regionService.fullName(provinceId, cityId, districtId);
    }

    /** 空结果时的统一提示构造，避免各工具各写一套。 */
    public String emptyNote(String what) {
        return "没有符合条件的结果" + (what == null || what.isBlank() ? "" : "（" + what + "）");
    }

    /** 供工具直接读取字典选项（给模型解释可选值）。 */
    public Map<String, String> dictOptions(String dictType) {
        return dictService.options(dictType);
    }

    /**
     * 去掉正文里的 HTML 标签并压缩空白。
     * cms_article.desc 与设备详情里都是富文本（带 img/p 标签），
     * 原样丢给模型会白烧一大截 token。
     */
    public static String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");
        text = text.replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }

    /** 截断长文本，避免单条结果过长。 */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
