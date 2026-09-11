package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.jixiejia.agent.persistence.entity.jxj.JxbQiuzu;
import com.jixiejia.agent.persistence.mapper.jxj.JxbQiuzuMapper;
import com.jixiejia.agent.persistence.support.BizFilters;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 求租查询工具（能力标识 qiuzu），供 RentalAgent 使用。
 *
 * <p>注意方向：jxb_chuzu 是"有机器的要往外租"，jxb_qiuzu 是"要干活的人在找机器"。
 * 两者不能混，工具描述里要把这个区别讲清楚，否则模型会张冠李戴。
 */
@Component
@RequiredArgsConstructor
public class QiuzuQueryTool implements BizTool {

    @Override
    public String capability() {
        return ToolCapability.QIUZU;
    }

    @Override
    public String displayName() {
        return "求租查询";
    }

    private static final String DICT_EQUIPMENT_TYPE = "zl_sblx";
    private static final String DICT_PROJECT_TYPE = "qz_gclx";
    private static final String DICT_PAY_TYPE = "qz_fkfs";
    private static final String DICT_TRAILER_FEE = "jxb_tcf";
    private static final String DICT_STATUS = "qz_status";

    private final JxbQiuzuMapper qiuzuMapper;
    private final ToolSupport support;

    public record QiuzuItem(
            Long id,
            String equipmentType,
            String equipmentModel,
            String projectType,
            String duration,
            String rent,
            String payType,
            String trailerFee,
            String status,
            LocalDateTime startDate,
            String location,
            String remark
    ) {
    }

    @Tool(name = "search_qiuzu", description = """
            查询平台上发布的求租信息，也就是"要用机器的人正在找机器"。
            当用户问"哪里有活干""有人要租XX吗""XX型号有没有人要"时调用——
            注意这是机主找活的方向，与 search_chuzu（设备对外出租）方向相反，不要混用。
            返回需要的设备类型、工程类型、工期、付款方式、拖车费承担方、所在地。""")
    public String searchQiuzu(
            @ToolParam(description = "需要的设备类型，如 挖掘机、装载机、旋挖钻、压路机", required = false) String equipmentType,
            @ToolParam(description = "工程类型，如 拆迁工程、矿山工程、公路工程、市政工程", required = false) String projectType,
            @ToolParam(description = "付款方式：现款、工程期付款、协商付款", required = false) String payType,
            @ToolParam(description = "只看进行中的填 进行中；不填表示不限", required = false) String status,
            @ToolParam(description = "所在地区，填口语地名如 洛阳、北京、河南", required = false) String area,
            @ToolParam(description = "返回条数，默认5，最大20", required = false) Integer limit) {

        int size = support.capLimit(limit);
        List<String> notes = new ArrayList<>();

        LambdaQueryWrapper<JxbQiuzu> w = BizFilters.visibleQiuzu();

        String eqType = support.normalizeDict(DICT_EQUIPMENT_TYPE, equipmentType);
        if (eqType != null) {
            w.eq(JxbQiuzu::getEquipmentType, eqType);
        } else if (support.blankToNull(equipmentType) != null) {
            notes.add("未识别设备类型「" + equipmentType.trim() + "」，可选："
                    + support.dictHint(DICT_EQUIPMENT_TYPE) + "，本次未按该条件过滤");
        }

        // 型号是自由文本，单独做模糊匹配
        String model = support.blankToNull(equipmentType);
        if (model != null && eqType == null) {
            w.like(JxbQiuzu::getEquipmentModel, model);
        }

        applyDict(w, DICT_PROJECT_TYPE, projectType, JxbQiuzu::getProjectType, "工程类型", notes);
        applyDict(w, DICT_PAY_TYPE, payType, JxbQiuzu::getPayType, "付款方式", notes);
        applyDict(w, DICT_STATUS, status, JxbQiuzu::getStatus, "状态", notes);

        ToolSupport.AreaMatch match = support.matchArea(area);
        if (match != null) {
            ToolSupport.applyArea(w, match,
                    JxbQiuzu::getProvinceId, JxbQiuzu::getCityId, JxbQiuzu::getDistrictId);
            notes.add("已按地区「" + match.name() + "」过滤");
        } else if (support.blankToNull(area) != null) {
            notes.add("未能识别地区「" + area.trim() + "」，本次未按地区过滤");
        }

        w.orderByDesc(JxbQiuzu::getCreateTime).orderByDesc(JxbQiuzu::getId).last("limit " + size);
        List<JxbQiuzu> rows = qiuzuMapper.selectList(w);

        List<QiuzuItem> items = rows.stream().map(this::toItem).toList();
        if (items.isEmpty()) {
            notes.add(support.emptyNote("平台上暂无符合条件的求租信息"));
        }

        return support.json(new ToolSupport.ListResult<>(
                items.size(), items, notes.isEmpty() ? null : String.join("；", notes)));
    }

    private void applyDict(LambdaQueryWrapper<JxbQiuzu> w, String dictType, String input,
                           SFunction<JxbQiuzu, ?> column, String label, List<String> notes) {
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

    private QiuzuItem toItem(JxbQiuzu q) {
        return new QiuzuItem(
                q.getId(),
                support.dict(DICT_EQUIPMENT_TYPE, q.getEquipmentType()),
                q.getEquipmentModel(),
                support.dict(DICT_PROJECT_TYPE, q.getProjectType()),
                q.getDuration(),
                q.getRent(),
                support.dict(DICT_PAY_TYPE, q.getPayType()),
                support.dict(DICT_TRAILER_FEE, q.getTrailerFee()),
                support.dict(DICT_STATUS, q.getStatus()),
                q.getStartDate(),
                support.location(q.getProvinceId(), q.getCityId(), q.getDistrictId()),
                q.getRemark());
    }
}
