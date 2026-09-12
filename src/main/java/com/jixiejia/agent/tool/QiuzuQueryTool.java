package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.jixiejia.agent.persistence.entity.jxj.JxbQiuzu;
import com.jixiejia.agent.persistence.entity.jxj.JxbXuqiu;
import com.jixiejia.agent.persistence.mapper.jxj.JxbQiuzuMapper;
import com.jixiejia.agent.persistence.mapper.jxj.JxbXuqiuMapper;
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
 * <p><b>方向不能混</b>：jxb_chuzu 是"有机器的要往外租"，jxb_qiuzu / jxb_xuqiu 是
 * "要用机器的人在找机器"。工具描述里要把这个区别讲清楚，否则模型会张冠李戴。
 *
 * <p><b>为什么是两张表</b>：平台上"求租"和"用机需求"是同一个业务概念
 * （都是"有人要租设备"），只是当初建了两张表、字段各有侧重：
 * <ul>
 *   <li>{@code jxb_qiuzu}：设备类型 + 工程类型 + 进场时间 + 工期 + 拖车费；</li>
 *   <li>{@code jxb_xuqiu}：设备类型 + <b>需求数量</b> + 施工地址 + 结款方式。</li>
 * </ul>
 * 之前它们各自挂在一个意图和工具下（DEMAND_QUERY / search_demand），
 * 结果是**模型面前摆着两个几乎同义的选项**——实测"工地马上开工了还差两台挖机"
 * 就是被判歪的（三个标签各说各的）。现在合成一个工具、一次查两张表，
 * 返回里用 {@code source} 标出来源。
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
    private static final String DICT_REQ_EQUIPMENT_TYPE = "req_equipment_type";
    private static final String DICT_PROJECT_TYPE = "qz_gclx";
    private static final String DICT_PAY_TYPE = "qz_fkfs";
    private static final String DICT_TRAILER_FEE = "jxb_tcf";
    private static final String DICT_STATUS = "qz_status";

    private final JxbQiuzuMapper qiuzuMapper;
    private final JxbXuqiuMapper xuqiuMapper;
    private final ToolSupport support;

    /** 两个来源的标识，出现在返回结果里 */
    private static final String SOURCE_QUIZU = "求租";
    private static final String SOURCE_XUQIU = "用机需求";

    /**
     * 一条"有人要租设备"的信息。
     *
     * <p>两张来源的字段不完全重合，取不到的留 null（例如"需求数量"只有用机需求表有、
     * "工程类型/进场时间"只有求租表有）。宁可留空也不要编一个值出来。
     */
    public record QiuzuItem(
            String source,
            Long id,
            String equipmentType,
            String equipmentModel,
            Integer reqNum,
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
            查询平台上"有人要租设备"的信息——也就是"要用机器的人正在找机器"。
            数据来自两处：求租信息 + 用机需求，返回里用 source 字段标出来源，它们是同一件事。
            当用户问"哪里有活干""有人要租XX吗""XX型号有没有人要""现在有哪些设备需求"时调用。
            注意这是"别人要租机器"的方向，与 search_chuzu（设备对外出租）方向相反，不要混用。
            返回需要的设备类型、数量、工程类型、工期、付款方式、所在地。""")
    public String searchQiuzu(
            @ToolParam(description = "需要的设备类型，如 挖掘机、装载机、旋挖钻、压路机", required = false) String equipmentType,
            @ToolParam(description = "工程类型，如 拆迁工程、矿山工程、公路工程、市政工程", required = false) String projectType,
            @ToolParam(description = "付款方式：现款、工程期付款、协商付款", required = false) String payType,
            @ToolParam(description = "只看进行中的填 进行中；不填表示不限", required = false) String status,
            @ToolParam(description = "所在地区，填口语地名如 洛阳、北京、河南", required = false) String area,
            @ToolParam(description = "返回条数，默认5，最大20", required = false) Integer limit) {

        int size = support.capLimit(limit);
        List<String> notes = new ArrayList<>();

        List<QiuzuItem> items = new ArrayList<>(queryQiuzu(
                equipmentType, projectType, payType, status, area, size, notes));
        // 用机需求表和求租表是同一件事的另一处数据源，一起查。
        // 数量上不再各取 size 条：先取求租，需求量按剩余名额补，避免总条数翻倍超预期。
        int remain = Math.max(0, size - items.size());
        if (remain > 0) {
            items.addAll(queryXuqiu(equipmentType, payType, area, remain, notes));
        }

        if (items.isEmpty()) {
            notes.add(support.emptyNote("平台上暂无符合条件的求租信息"));
        }

        return support.json(new ToolSupport.ListResult<>(
                items.size(), items, notes.isEmpty() ? null : String.join("；", notes)));
    }

    /** 求租表（jxb_qiuzu）：字段最全的一路。 */
    private List<QiuzuItem> queryQiuzu(String equipmentType, String projectType, String payType,
                                       String status, String area, int size, List<String> notes) {
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
        return qiuzuMapper.selectList(w).stream().map(this::toItem).toList();
    }

    /**
     * 用机需求表（jxb_xuqiu）：和求租是同一件事，只是字段侧重不同。
     *
     * <p>这张表**没有区划列**（只有自由文本 address），所以地区只能按地址文本模糊匹配——
     * 和求租表走的是两套逻辑，别抄错。
     */
    private List<QiuzuItem> queryXuqiu(String equipmentType, String payType,
                                       String area, int size, List<String> notes) {
        LambdaQueryWrapper<JxbXuqiu> w = BizFilters.visibleXuqiu();

        // ⚠️ 两张表的设备类型**字典不同**（求租用 zl_sblx，需求用 req_equipment_type）。
        // 用户给了明确的设备类型、而这边的字典认不出来时：**跳过这张表**，
        // 而不是"不过滤"——不过滤等于把不相关的需求也塞进结果里，
        // 而用户明明说了要什么设备。求租表那边还能按型号自由文本 LIKE 兜一下，
        // 这张表连型号列都没有。
        if (support.blankToNull(equipmentType) != null
                && support.normalizeDict(DICT_REQ_EQUIPMENT_TYPE, equipmentType) == null) {
            notes.add("「" + equipmentType.trim() + "」在求租信息里没有对应条目，本次未查该来源");
            return List.of();
        }
        String eqType = support.normalizeDict(DICT_REQ_EQUIPMENT_TYPE, equipmentType);
        if (eqType != null) {
            w.eq(JxbXuqiu::getReqType, eqType);
        }

        applyDict(w, DICT_PAY_TYPE, payType, JxbXuqiu::getPayType, "付款方式", notes);

        String areaText = support.blankToNull(area);
        if (areaText != null) {
            // 本表无区划列，只能按自由文本地址模糊匹配
            w.like(JxbXuqiu::getAddress, areaText);
        }

        w.orderByDesc(JxbXuqiu::getCreateTime).orderByDesc(JxbXuqiu::getId).last("limit " + size);
        return xuqiuMapper.selectList(w).stream().map(this::toItem).toList();
    }

    /**
     * 按字典把口语说法归一成码值再加过滤条件；认不出来就记一条提示、**不静默当作不过滤**。
     *
     * <p>写成泛型是因为两张表各有一个 wrapper，而泛型参数擦除后签名相同，
     * 重载会被编译器判成"名称冲突"。
     */
    private <T> void applyDict(LambdaQueryWrapper<T> w, String dictType, String input,
                               SFunction<T, ?> column, String label, List<String> notes) {
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
                SOURCE_QUIZU,
                q.getId(),
                support.dict(DICT_EQUIPMENT_TYPE, q.getEquipmentType()),
                q.getEquipmentModel(),
                null,   // 求租表没有"需求数量"
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

    private QiuzuItem toItem(JxbXuqiu x) {
        return new QiuzuItem(
                SOURCE_XUQIU,
                x.getId(),
                support.dict(DICT_REQ_EQUIPMENT_TYPE, x.getReqType()),
                null,   // 需求表没有型号
                x.getReqNum(),
                null,   // 需求表没有工程类型
                x.getDuration(),
                null,   // 需求表没有租金
                support.dict(DICT_PAY_TYPE, x.getPayType()),
                null,   // 需求表没有拖车费
                x.getStatus(),
                null,   // 需求表没有进场时间
                // 需求表没有区划列，位置只有自由文本 address（可能为空）
                support.blankToNull(x.getAddress()),
                x.getReqRemark() != null ? x.getReqRemark() : x.getRemark());
    }
}
