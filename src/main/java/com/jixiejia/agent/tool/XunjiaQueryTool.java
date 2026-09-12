package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jixiejia.agent.persistence.entity.jxj.JxbXunjia;
import com.jixiejia.agent.persistence.mapper.jxj.JxbXunjiaMapper;
import com.jixiejia.agent.persistence.support.BizFilters;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 新机询价查询工具（能力标识 equipment），供 EquipmentAgent 使用。
 *
 * <p><b>为什么挂在"设备买卖"而不是"租赁"下</b>：它查的是
 * {@code jxb_xunjia}——"有人<b>想买新机</b>、留了联系方式和意向机型"的线索，
 * 属于**买**这一侧，和"有人要租设备"（求租）完全是两回事。
 *
 * <p>它原来和"用机需求"挤在同一个 {@code DemandQueryTool} 里、挂在 {@code demand} 能力下。
 * 那个能力已经去掉了——因为"需求"和"求租"在平台上是同一个东西，
 * 两个几乎同义的意图摆在模型面前只会让句子被判歪（详见 {@link QiuzuQueryTool} 的类注释）。
 * 询价不是那件事，所以单独拿到这边来。
 */
@Component
@RequiredArgsConstructor
public class XunjiaQueryTool implements BizTool {

    @Override
    public String capability() {
        return ToolCapability.EQUIPMENT;
    }

    @Override
    public String displayName() {
        return "新机询价查询";
    }

    private static final String DICT_REQ_EQUIPMENT_TYPE = "req_equipment_type";

    private final JxbXunjiaMapper xunjiaMapper;
    private final ToolSupport support;

    public record InquiryItem(
            Long id,
            String contactName,
            String equipmentType,
            String location,
            String remark
    ) {
    }

    @Tool(name = "search_xunjia", description = """
            查询平台上的新机询价记录（有人想买新机、留了联系方式和意向机型）。
            当用户问"有哪些人想买新机""新机询价多吗"时调用。
            注意这是"想买新机"的线索，不是"有人要租设备"——后者用 search_qiuzu。""")
    public String searchXunjia(
            @ToolParam(description = "意向机械类型，如 大挖掘机、装载机、起重机", required = false) String equipmentType,
            @ToolParam(description = "所在地区，填口语地名如 洛阳、北京、河南", required = false) String area,
            @ToolParam(description = "返回条数，默认5，最大20", required = false) Integer limit) {

        int size = support.capLimit(limit);
        List<String> notes = new ArrayList<>();

        LambdaQueryWrapper<JxbXunjia> w = BizFilters.visibleXunjia();

        String type = support.normalizeDict(DICT_REQ_EQUIPMENT_TYPE, equipmentType);
        if (type != null) {
            w.eq(JxbXunjia::getEquipmentType, type);
        } else if (support.blankToNull(equipmentType) != null) {
            notes.add("未识别意向机械类型「" + equipmentType.trim() + "」，可选："
                    + support.dictHint(DICT_REQ_EQUIPMENT_TYPE) + "，本次未按该条件过滤");
        }

        ToolSupport.AreaMatch match = support.matchArea(area);
        if (match != null) {
            ToolSupport.applyArea(w, match,
                    JxbXunjia::getProvinceId, JxbXunjia::getCityId, JxbXunjia::getDistrictId);
            notes.add("已按地区「" + match.name() + "」过滤");
        } else if (support.blankToNull(area) != null) {
            notes.add("未能识别地区「" + area.trim() + "」，本次未按地区过滤");
        }

        w.orderByDesc(JxbXunjia::getCreateTime).orderByDesc(JxbXunjia::getId).last("limit " + size);
        List<JxbXunjia> rows = xunjiaMapper.selectList(w);

        List<InquiryItem> items = rows.stream().map(x -> new InquiryItem(
                x.getId(),
                x.getName(),
                support.dict(DICT_REQ_EQUIPMENT_TYPE, x.getEquipmentType()),
                support.location(x.getProvinceId(), x.getCityId(), x.getDistrictId()),
                x.getRemark())).toList();

        if (items.isEmpty()) {
            notes.add(support.emptyNote("平台上暂无符合条件的新机询价"));
        }

        return support.json(new ToolSupport.ListResult<>(
                items.size(), items, notes.isEmpty() ? null : String.join("；", notes)));
    }
}
