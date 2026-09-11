package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jixiejia.agent.persistence.entity.jxj.JxbXunjia;
import com.jixiejia.agent.persistence.entity.jxj.JxbXuqiu;
import com.jixiejia.agent.persistence.mapper.jxj.JxbXunjiaMapper;
import com.jixiejia.agent.persistence.mapper.jxj.JxbXuqiuMapper;
import com.jixiejia.agent.persistence.support.BizFilters;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用机需求与新机询价查询工具（能力标识 demand），供 RentalAgent 使用。
 *
 * <p>两张表的地区字段口径不同，别搞混：
 * <ul>
 *   <li>jxb_xuqiu <b>没有</b> province_id/city_id/district_id，位置只有自由文本 address
 *       + 经纬度，所以地区只能按 address 做模糊匹配；</li>
 *   <li>jxb_xunjia <b>有</b> province_id/city_id/district_id，走常规区划过滤。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DemandQueryTool implements BizTool {

    @Override
    public String capability() {
        return ToolCapability.DEMAND;
    }

    @Override
    public String displayName() {
        return "需求询价查询";
    }

    private static final String DICT_REQ_EQUIPMENT_TYPE = "req_equipment_type";
    private static final String DICT_PAY_TYPE = "qz_fkfs";
    private static final String DICT_OWNER_TYPE = "jxb_sbss";

    private final JxbXuqiuMapper xuqiuMapper;
    private final JxbXunjiaMapper xunjiaMapper;
    private final ToolSupport support;

    public record DemandItem(
            Long id,
            String reqType,
            Integer reqNum,
            String contactName,
            String duration,
            String address,
            String payType,
            String ownerType,
            String status,
            String remark
    ) {
    }

    public record InquiryItem(
            Long id,
            String contactName,
            String equipmentType,
            String location,
            String remark
    ) {
    }

    @Tool(name = "search_demand", description = """
            查询平台上发布的用机需求（用工方/施工方需要机器）。
            当用户问"现在有哪些设备需求""有人要挖机吗""近期有什么活"时调用。
            返回需要的设备类型、数量、施工地址、工期、结款方式。
            注意本表只有自由文本地址，地区参数会按地址文本模糊匹配，匹配不到时不要臆断。""")
    public String searchDemand(
            @ToolParam(description = "设备类型，如 大挖掘机、中型挖掘机、小型挖掘机、装载机、推土机、压路机、起重机、自卸车", required = false) String reqType,
            @ToolParam(description = "结款方式：现款、工程期付款、协商付款", required = false) String payType,
            @ToolParam(description = "施工地点关键词，如 洛阳、郑州；按地址文本模糊匹配", required = false) String area,
            @ToolParam(description = "返回条数，默认5，最大20", required = false) Integer limit) {

        int size = support.capLimit(limit);
        List<String> notes = new ArrayList<>();

        LambdaQueryWrapper<JxbXuqiu> w = BizFilters.visibleXuqiu();

        String type = support.normalizeDict(DICT_REQ_EQUIPMENT_TYPE, reqType);
        if (type != null) {
            w.eq(JxbXuqiu::getReqType, type);
        } else if (support.blankToNull(reqType) != null) {
            notes.add("未识别设备类型「" + reqType.trim() + "」，可选："
                    + support.dictHint(DICT_REQ_EQUIPMENT_TYPE) + "，本次未按该条件过滤");
        }

        String pay = support.normalizeDict(DICT_PAY_TYPE, payType);
        if (pay != null) {
            w.eq(JxbXuqiu::getPayType, pay);
        } else if (support.blankToNull(payType) != null) {
            notes.add("未识别结款方式「" + payType.trim() + "」，本次未按该条件过滤");
        }

        String areaText = support.blankToNull(area);
        if (areaText != null) {
            // 本表无区划列，只能按自由文本地址模糊匹配
            w.like(JxbXuqiu::getAddress, areaText);
            notes.add("用机需求表未记录行政区划，已按地址文本包含「" + areaText + "」匹配");
        }

        w.orderByDesc(JxbXuqiu::getCreateTime).orderByDesc(JxbXuqiu::getId).last("limit " + size);
        List<JxbXuqiu> rows = xuqiuMapper.selectList(w);

        List<DemandItem> items = rows.stream().map(this::toItem).toList();
        if (items.isEmpty()) {
            notes.add(support.emptyNote("平台上暂无符合条件的用机需求"));
        }

        return support.json(new ToolSupport.ListResult<>(
                items.size(), items, notes.isEmpty() ? null : String.join("；", notes)));
    }

    @Tool(name = "search_xunjia", description = """
            查询平台上的新机询价记录（有人想买新机、留了联系方式和意向机型）。
            当用户问"有哪些人想买新机""新机询价多吗"时调用。
            注意这是"想买新机"的线索，与 search_demand（要找机器干活）不是一回事。""")
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

    private DemandItem toItem(JxbXuqiu x) {
        return new DemandItem(
                x.getId(),
                support.dict(DICT_REQ_EQUIPMENT_TYPE, x.getReqType()),
                x.getReqNum(),
                x.getName(),
                x.getDuration(),
                x.getAddress(),
                support.dict(DICT_PAY_TYPE, x.getPayType()),
                support.dict(DICT_OWNER_TYPE, x.getOwnerType()),
                x.getStatus(),
                x.getRemark());
    }
}
