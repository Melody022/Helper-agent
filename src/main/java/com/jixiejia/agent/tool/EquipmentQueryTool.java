package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jixiejia.agent.persistence.entity.jxj.JxbEquipment;
import com.jixiejia.agent.persistence.mapper.jxj.JxbEquipmentMapper;
import com.jixiejia.agent.persistence.support.BizFilters;
import com.jixiejia.agent.persistence.support.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备查询工具（能力标识 equipment），供 EquipmentAgent 使用。
 *
 * <p>底层是 jxb_equipment，只会返回上架且未删除的设备（见 {@link BizFilters#visibleEquipment()}）。
 * 注意库里 72 台设备只有 23 台符合该口径，其余是 type=0 的目录行。
 *
 * <p>刻意不返回卖家电话：本 Agent 面向任意访客开放，批量拉取联系电话属于信息泄露，
 * 联系方式应由平台页面承载。如需放开，请先明确鉴权口径。
 */
@Component
@RequiredArgsConstructor
public class EquipmentQueryTool implements BizTool {

    @Override
    public String capability() {
        return ToolCapability.EQUIPMENT;
    }

    @Override
    public String displayName() {
        return "设备查询";
    }

    /** 设备来源 char(1)，库内未配字典，含义见列注释 */
    private static final String SOURCE_PERSONAL = "0";
    private static final String SOURCE_PLATFORM = "1";
    private static final String SOURCE_SELF_OPERATED = "2";

    private final JxbEquipmentMapper equipmentMapper;
    private final CategoryService categoryService;
    private final ToolSupport support;

    @Tool(name = "search_equipment", description = """
            按条件查询平台上在售的工程机械设备（二手为主，也有新机）。
            当用户问"有没有XX设备""XX多少钱""哪里能买到XX""帮我推荐一台XX"时调用。
            设备名称、价格、表显小时数、出厂年份、所在地都会返回。
            地区参数填用户口语里的地名（如"洛阳""河南"）即可，不需要行政区划编码。""")
    public String searchEquipment(
            @ToolParam(description = "机型或型号关键词，如 挖掘机、装载机、旋挖钻、小松、ZX200", required = false) String keyword,
            @ToolParam(description = "设备类型：0新机 1二手机 2发车帮；不填表示不限", required = false) Integer type,
            @ToolParam(description = "所在地区，填口语地名如 洛阳、北京、河南", required = false) String area,
            @ToolParam(description = "最低售价，单位万元", required = false) BigDecimal minPriceWanYuan,
            @ToolParam(description = "最高售价，单位万元", required = false) BigDecimal maxPriceWanYuan,
            @ToolParam(description = "最早出厂年份，如 2015", required = false) Integer minFactoryYear,
            @ToolParam(description = "最大表显小时数", required = false) Integer maxUsedHours,
            @ToolParam(description = "返回条数，默认5，最大20", required = false) Integer limit) {

        int size = support.capLimit(limit);
        List<String> notes = new ArrayList<>();

        LambdaQueryWrapper<JxbEquipment> w = BizFilters.visibleEquipment();

        String kw = support.blankToNull(keyword);
        if (kw != null) {
            // 关键词可能命中设备名（自由文本），也可能是机型/品牌（存在 sys_category 里的 id）
            Long machineId = categoryService.findMachineId(kw);
            Long brandId = categoryService.findBrandId(kw);
            w.and(inner -> {
                inner.like(JxbEquipment::getName, kw);
                if (machineId != null) {
                    inner.or().eq(JxbEquipment::getFirstCateId, machineId);
                }
                if (brandId != null) {
                    inner.or().eq(JxbEquipment::getSecondCateId, brandId);
                }
            });
        }

        if (type != null) {
            w.eq(JxbEquipment::getType, type);
        }
        if (minPriceWanYuan != null) {
            w.ge(JxbEquipment::getPrice, minPriceWanYuan);
        }
        if (maxPriceWanYuan != null) {
            w.le(JxbEquipment::getPrice, maxPriceWanYuan);
        }
        if (minFactoryYear != null) {
            w.ge(JxbEquipment::getFactoryDate, minFactoryYear);
        }
        if (maxUsedHours != null) {
            w.le(JxbEquipment::getUsedHours, maxUsedHours);
        }

        ToolSupport.AreaMatch match = support.matchArea(area);
        if (match != null) {
            ToolSupport.applyArea(w, match,
                    JxbEquipment::getProvinceId, JxbEquipment::getCityId, JxbEquipment::getDistrictId);
        } else if (support.blankToNull(area) != null) {
            notes.add("未能识别地区「" + area.trim() + "」，本次未按地区过滤");
        }

        w.orderByDesc(JxbEquipment::getPublishTime).orderByDesc(JxbEquipment::getId).last("limit " + size);
        List<JxbEquipment> rows = equipmentMapper.selectList(w);

        if (match != null) {
            notes.add("已按地区「" + match.name() + "」过滤");
            // 设备表有相当比例的行没填所在地，被地区条件排除掉是数据缺失而非真的没有
            if (rows.isEmpty()) {
                notes.add("注意：设备表中部分设备未填写所在地，按地区筛选可能漏掉这些设备");
            }
        }

        List<EquipmentItem> items = rows.stream().map(this::toItem).toList();
        if (items.isEmpty()) {
            notes.add(support.emptyNote(null));
        }

        return support.json(new ToolSupport.ListResult<>(items.size(), items, joinNotes(notes)));
    }

    @Tool(name = "get_equipment_detail", description = """
            按设备 id 查一台设备的详细信息（含图文介绍正文）。
            在 search_equipment 返回结果里拿到 id 之后，用户想深入了解某台设备时调用。""")
    public String getEquipmentDetail(
            @ToolParam(description = "设备 id，取自 search_equipment 返回的 id") Long equipmentId) {

        if (equipmentId == null) {
            return support.json(new ToolSupport.ItemResult<>(null, "缺少设备 id"));
        }

        JxbEquipment e = equipmentMapper.selectOne(
                BizFilters.visibleEquipment().eq(JxbEquipment::getId, equipmentId));

        if (e == null) {
            return support.json(new ToolSupport.ItemResult<>(null,
                    "设备不存在或已下架（id=" + equipmentId + "）"));
        }

        String desc = ToolSupport.stripHtml(e.getMobileDesc() != null ? e.getMobileDesc() : e.getPcDesc());
        DetailItem item = new DetailItem(
                e.getId(),
                e.getName(),
                e.getSubtitle(),
                categoryService.name(e.getFirstCateId()),
                categoryService.name(e.getSecondCateId()),
                categoryService.name(e.getThirdCateId()),
                e.getPrice(),
                e.getUserPrice(),
                e.getUsedHours(),
                e.getFactoryDate(),
                support.location(e.getProvinceId(), e.getCityId(), e.getDistrictId()),
                support.parseTags(e.getTag()),
                sourceLabel(e.getSource()),
                ToolSupport.truncate(desc, 800));
        return support.json(new ToolSupport.ItemResult<>(item, null));
    }

    private EquipmentItem toItem(JxbEquipment e) {
        return new EquipmentItem(
                e.getId(),
                e.getName(),
                categoryService.name(e.getFirstCateId()),
                categoryService.name(e.getSecondCateId()),
                categoryService.name(e.getThirdCateId()),
                e.getPrice(),
                e.getUsedHours(),
                e.getFactoryDate(),
                support.location(e.getProvinceId(), e.getCityId(), e.getDistrictId()),
                support.parseTags(e.getTag()),
                sourceLabel(e.getSource()));
    }

    private static String sourceLabel(String source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case SOURCE_PERSONAL -> "个人发布";
            case SOURCE_PLATFORM -> "平台车源";
            case SOURCE_SELF_OPERATED -> "自营认证";
            default -> null;
        };
    }

    private static String joinNotes(List<String> notes) {
        return notes.isEmpty() ? null : String.join("；", notes);
    }

    public record EquipmentItem(
            Long id,
            String name,
            String machine,
            String brand,
            String model,
            BigDecimal priceWanYuan,
            Integer usedHours,
            Integer factoryYear,
            String location,
            List<String> tags,
            String source
    ) {
    }

    public record DetailItem(
            Long id,
            String name,
            String subtitle,
            String machine,
            String brand,
            String model,
            BigDecimal priceWanYuan,
            BigDecimal userPriceWanYuan,
            Integer usedHours,
            Integer factoryYear,
            String location,
            List<String> tags,
            String source,
            String description
    ) {
    }
}
