package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jixiejia.agent.persistence.entity.jxj.JxbChuzu;
import com.jixiejia.agent.persistence.mapper.jxj.JxbChuzuMapper;
import com.jixiejia.agent.persistence.support.BizFilters;
import com.jixiejia.agent.persistence.support.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 出租查询工具（能力标识 chuzu），供 RentalAgent 使用。
 *
 * <p>底层 jxb_chuzu，只返回审核通过且未删除的记录。
 * 吨位、个人/公司、待租/忙碌、审核状态在库里都是字典码，出参已翻成中文。
 *
 * <p>同样不返回联系电话，理由见 {@link EquipmentQueryTool}。
 */
@Component
@RequiredArgsConstructor
public class ChuzuQueryTool implements BizTool {

    @Override
    public String capability() {
        return ToolCapability.CHUZU;
    }

    @Override
    public String displayName() {
        return "出租查询";
    }

    private static final String DICT_TONNAGE = "jxb_jxdw";
    private static final String DICT_OWNER_TYPE = "jxb_sbss";
    private static final String DICT_STATUS = "cz_sbzt";

    private final JxbChuzuMapper chuzuMapper;
    private final CategoryService categoryService;
    private final ToolSupport support;

    public record ChuzuItem(
            Long id,
            String machine,
            String brand,
            String model,
            String equipmentModel,
            String tonnage,
            String ownerType,
            String status,
            String rent,
            Integer factoryYear,
            String location,
            String detailAddress,
            String remark
    ) {
    }

    @Tool(name = "search_chuzu", description = """
            查询平台上正在出租的工程机械设备（机主发布的招租信息）。
            当用户问"附近有XX出租吗""哪里有XX可以租""租一台XX多少钱"时调用。
            返回设备类型、吨位、租金、机主是个人还是公司、当前是否待租、所在地。
            地区参数填用户口语地名（如"洛阳"）即可，不需要行政区划编码。""")
    public String searchChuzu(
            @ToolParam(description = "设备类型或型号关键词，如 挖掘机、装载机、旋挖钻、徐工520D", required = false) String keyword,
            @ToolParam(description = "吨位档位，可选值：6吨以下、6-9吨、10-19吨、20-29吨、30-49吨、50吨以上", required = false) String tonnage,
            @ToolParam(description = "出租方类型：个人 或 公司", required = false) String ownerType,
            @ToolParam(description = "只要待租的填 待租；不填表示不限", required = false) String status,
            @ToolParam(description = "所在地区，填口语地名如 洛阳、北京、河南", required = false) String area,
            @ToolParam(description = "返回条数，默认5，最大20", required = false) Integer limit) {

        int size = support.capLimit(limit);
        List<String> notes = new ArrayList<>();

        LambdaQueryWrapper<JxbChuzu> w = BizFilters.visibleChuzu();

        String kw = support.blankToNull(keyword);
        if (kw != null) {
            Long machineId = categoryService.findMachineId(kw);
            Long brandId = categoryService.findBrandId(kw);
            w.and(inner -> {
                inner.like(JxbChuzu::getEquipmentModel, kw);
                if (machineId != null) {
                    inner.or().eq(JxbChuzu::getFirstCateId, machineId);
                }
                if (brandId != null) {
                    inner.or().eq(JxbChuzu::getSecondCateId, brandId);
                }
            });
        }

        applyDict(w, DICT_TONNAGE, tonnage, JxbChuzu::getTonnage, "吨位", notes);
        applyDict(w, DICT_OWNER_TYPE, ownerType, JxbChuzu::getOwnerType, "出租方类型", notes);
        applyDict(w, DICT_STATUS, status, JxbChuzu::getStatus, "状态", notes);

        ToolSupport.AreaMatch match = support.matchArea(area);
        if (match != null) {
            ToolSupport.applyArea(w, match,
                    JxbChuzu::getProvinceId, JxbChuzu::getCityId, JxbChuzu::getDistrictId);
            notes.add("已按地区「" + match.name() + "」过滤");
        } else if (support.blankToNull(area) != null) {
            notes.add("未能识别地区「" + area.trim() + "」，本次未按地区过滤");
        }

        w.orderByDesc(JxbChuzu::getCreateTime).orderByDesc(JxbChuzu::getId).last("limit " + size);
        List<JxbChuzu> rows = chuzuMapper.selectList(w);

        List<ChuzuItem> items = rows.stream().map(this::toItem).toList();
        if (items.isEmpty()) {
            notes.add(support.emptyNote("平台上暂无符合条件的出租信息"));
        }

        return support.json(new ToolSupport.ListResult<>(
                items.size(), items, notes.isEmpty() ? null : String.join("；", notes)));
    }

    private void applyDict(LambdaQueryWrapper<JxbChuzu> w, String dictType, String input,
                           com.baomidou.mybatisplus.core.toolkit.support.SFunction<JxbChuzu, ?> column,
                           String label, List<String> notes) {
        String raw = support.blankToNull(input);
        if (raw == null) {
            return;
        }
        String code = support.normalizeDict(dictType, raw);
        if (code == null) {
            notes.add("未识别" + label + "「" + raw + "」，可选：" + support.dictHint(dictType) + "，本次未按该条件过滤");
            return;
        }
        w.eq(column, code);
    }

    private ChuzuItem toItem(JxbChuzu c) {
        return new ChuzuItem(
                c.getId(),
                categoryService.name(c.getFirstCateId()),
                categoryService.name(c.getSecondCateId()),
                categoryService.name(c.getThirdCateId()),
                c.getEquipmentModel(),
                support.dict(DICT_TONNAGE, c.getTonnage()),
                support.dict(DICT_OWNER_TYPE, c.getOwnerType()),
                support.dict(DICT_STATUS, c.getStatus()),
                c.getRent(),
                c.getFactoryDate(),
                support.location(c.getProvinceId(), c.getCityId(), c.getDistrictId()),
                c.getDetailAddress(),
                c.getRemark());
    }
}
