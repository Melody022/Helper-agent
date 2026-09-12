package com.jixiejia.agent.classify;

import com.jixiejia.agent.tool.ToolCapability;

import java.util.Set;

/**
 * 意图分类体系。每个意图声明两件事：<b>需要哪些能力</b>（进而决定由哪个 Agent 处理）、
 * <b>哪些关键词指向它</b>（供第 1 层关键词分类器使用）。
 *
 * <p>关键词分两档，权重来自 application.yml 的 routing.keyword.high-weight / mid-weight
 * （默认 0.9 / 0.7）：
 * <ul>
 *   <li><b>高权重</b>：几乎只指向这一个意图的词，如"求租""转人工"。命中即可直出，不进模型；</li>
 *   <li><b>中权重</b>：相关但可能跨域的词，如"价格""设备"。只用来给模型层提供倾向，不单独定案。</li>
 * </ul>
 * 高权重词必须选得保守——宁可漏判交给模型，也不要错判把用户送去错误的 Agent。
 * 像"租"这种同时指向出租和求租的词，一律不给高权重。
 */
public enum Intent {

    /** 找设备 / 问设备价格（含新机询价） */
    EQUIPMENT_QUERY("设备查询", "用户想买或想看设备，关心机型、价格、车源、表显小时数；也包括新机询价",
            Set.of(ToolCapability.EQUIPMENT),
            // 注意不要放"挖掘机""装载机"这类机型名词本身——它们在出租、求租、需求里都会出现，
            // 拿它当设备意图的高权重词会让"附近有挖掘机出租吗"被误判成设备查询
            Set.of("二手", "想买", "车源", "买挖掘机", "买装载机", "有没有卖"),
            Set.of("设备", "机器", "价格", "报价", "多少钱", "新机", "铲车", "买", "出售",
                    // 新机询价原来挂在已删掉的 DEMAND_QUERY 下，本质是"想买"，
                    // 归到这里和"想买设备"是一类
                    "询价", "新机询价", "求购")),

    /** 设备对外出租（机主招租） */
    CHUZU_QUERY("出租查询", "用户是承租方，想租一台设备，找别人（机主）发布出来的出租信息", Set.of(ToolCapability.CHUZU),
            Set.of("出租", "招租", "对外租"),
            Set.of("租金", "租", "租赁", "附近有", "可以租")),

    /**
     * 求租：平台上"有人要租设备"的信息。
     *
     * <p>⚠️ 这里原来还有一个 {@code DEMAND_QUERY}（"用机需求 / 新机询价"），**已经去掉了**——
     * <b>"需求"和"求租"在平台上是同一件事</b>：{@code jxb_qiuzu} 表的列就是
     * 设备类型 + 工程类型 + 进场时间 + 工期 + 拖车费，装的就是"要租机器的人发的需求"。
     * 两个意图并存的结果，是模型面前摆着两个几乎同义的选项——实测那句
     * "工地马上开工了还差两台挖机"就被判歪了（三个标签各说各的）。
     *
     * <p>新机询价**不是**一回事（那是"想买新机"），归到设备买卖那边了。
     */
    QIUZU_QUERY("求租查询",
            "用户想看平台上**求租**的信息——也就是\"要用机器的人正在找机器\"："
                    + "谁需要设备、什么工程、什么工期",
            Set.of(ToolCapability.QIUZU),
            Set.of("求租", "找活", "找机", "有活干", "谁要用", "需求", "用机需求", "谁要买"),
            Set.of("要租", "用工", "工期", "找台", "找人", "结款", "施工", "有哪些需求")),

    /** 平台资讯 / 文章：想看"有哪些文章"，不是问某个知识点的答案 */
    NEWS_QUERY("资讯查询", "用户想看行业资讯、文章、评测的列表", Set.of(ToolCapability.NEWS),
            Set.of("资讯", "新闻", "文章", "评测"),
            Set.of("行业动态", "最近有什么")),

    /**
     * 知识问答。覆盖平台规则与设备知识两类。
     *
     * <p>之所以把"平台规则"和"设备维修保养"并成一类：知识库里装的主要是
     * 平台发布的文章（评测、维修、保养、行业知识），而规则类内容也走同一套检索。
     * 两条路的处理方式完全一样——先查知识库、资料不足就不答——
     * 拆成两个意图只会让"小松200-8空调故障"这种问题两边都不认，
     * 最后落到通用兜底，白白错过知识库里现成的文章。
     */
    KNOWLEDGE_QUERY("知识问答", "用户问平台规则、流程、费用、押金，或设备的维修保养、故障处理、使用技巧这类知识",
            Set.of(ToolCapability.POLICY),
            Set.of("流程", "规则", "怎么收费", "手续费", "押金", "怎么退"),
            Set.of("规定", "政策", "怎么办", "如何", "平台怎么", "维修", "保养", "故障", "怎么修", "坏了")),
    /** 发布出租 */
    PUBLISH_CHUZU("发布出租",
            "用户是机主，想把自己的设备发布/挂到平台上出租，让平台帮他租出去",
            Set.of(ToolCapability.PUBLISH),
            // ⚠️ 这两个词表里**没有"发布"**：它太泛了。
            // "发布设备要审核多久"里的"发布"是名词、说的是审核流程，不是发布动作，
            // 而它曾经是这里的定案词，于是那句话被 0.90 高置信判成了发布出租。
            //
            // 也别再往这里加整串（"帮我发布出租"这种）——发布意图的表达是开放集合，
            // 枚举永远会漏。漏掉的交给 KeywordWeightClassifier.looksLikePublishIntent
            // 认出来，当**提示**交给模型（不是自己定案，见那个方法的注释）。
            Set.of("发布出租", "帮我发布", "我要发布", "我要出租"),
            Set.of("上架", "挂出去")),

    /** 发布求租 */
    PUBLISH_QIUZU("发布求租",
            "用户要发布一条求租信息：他自己要找一台机器或找活，希望平台帮他把这条需求发出去",
            Set.of(ToolCapability.PUBLISH),
            Set.of("发布求租", "我要发布求租", "发布需求"),
            Set.of("发个求租", "找个机器")),

    /** 跨域综合：一次问多个域，需要多个 Agent 接力。关键词判定交给模型层。 */
    CROSS_DOMAIN("跨域综合", "一句话里同时问了两个及以上不同领域的问题，如既问价格又问流程", Set.of(ToolCapability.EQUIPMENT, ToolCapability.CHUZU,
            ToolCapability.QIUZU, ToolCapability.NEWS),
            Set.of(), Set.of()),

    /** 投诉 / 纠纷，走固定话术 */
    COMPLAINT("投诉", "用户投诉、举报、表达强烈不满", Set.of(),
            Set.of("投诉", "举报", "诈骗", "被骗", "骗子", "维权", "差评"),
            Set.of("不满意", "太差", "骗人")),

    /** 转人工，短路入队 */
    HANDOFF("转人工", "用户要求转人工客服", Set.of(),
            Set.of("转人工", "人工客服", "找客服", "真人", "人工服务"),
            Set.of("客服", "打电话")),

    /** 问候 / 闲聊 / 帮助 */
    CHITCHAT("闲聊", "问候、致谢、闲聊、问助手能做什么", Set.of(),
            Set.of("你好", "您好", "在吗", "你是谁", "你会什么", "帮助", "怎么用"),
            Set.of("谢谢", "好的", "再见", "介绍一下")),

    /** 兜底：三层都没给出可信结果时落这里 */
    UNKNOWN("未知", "以上都不属于，或完全无法理解", Set.of(), Set.of(), Set.of());

    private final String label;
    private final String description;
    private final Set<String> capabilities;
    private final Set<String> highKeywords;
    private final Set<String> midKeywords;

    Intent(String label, String description, Set<String> capabilities,
           Set<String> highKeywords, Set<String> midKeywords) {
        this.label = label;
        this.description = description;
        this.capabilities = capabilities;
        this.highKeywords = highKeywords;
        this.midKeywords = midKeywords;
    }

    public String label() {
        return label;
    }

    /** 给模型看的一句话说明：什么情况下算这个意图。 */
    public String description() {
        return description;
    }

    public Set<String> capabilities() {
        return capabilities;
    }

    public Set<String> highKeywords() {
        return highKeywords;
    }

    public Set<String> midKeywords() {
        return midKeywords;
    }

    /** 是否是需要模型介入的兜底意图。 */
    public boolean isFallback() {
        return this == UNKNOWN || this == CHITCHAT;
    }

    /** 是否是"不经过 Agent、直接特殊处理"的意图（转人工 / 投诉）。 */
    public boolean isShortCircuit() {
        return this == HANDOFF || this == COMPLAINT;
    }

    /**
     * 是否是"动作型"意图。发布类属于这一类。
     *
     * <p>动作意图与查询意图的关键区别：用户说"帮我发布一台出租"时，
     * 他要的是<b>发起发布流程</b>，而不是查出租信息。如果按普通关键词竞争，
     * "出租"这个词会把意图抢到出租查询上——实测评估里就踩到了这个错判。
     *
     * <p>所以动作意图命中高权重词时直接定案，且不参与跨域判定
     * （否则"发布 + 出租"会被当成两个域，触发一次毫无意义的综合）。
     */
    public boolean isActionIntent() {
        return this == PUBLISH_CHUZU || this == PUBLISH_QIUZU;
    }

    /**
     * 按能力标识反查意图。跨域时模型给出的是领域（capability），
     * 路由需要把它还原成意图才能走正常的 Agent 匹配。
     *
     * @return 覆盖该能力的第一个查询类意图；没有则 empty
     */
    public static java.util.Optional<Intent> byCapability(String capability) {
        if (capability == null || capability.isBlank()) {
            return java.util.Optional.empty();
        }
        String target = capability.trim();
        for (Intent i : values()) {
            if (i.capabilities().contains(target)) {
                return java.util.Optional.of(i);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * 把模型返回的字符串解析成意图。
     *
     * <p>两个约束在这里落地：
     * <ol>
     *   <li>模型不得返回系统命令——凡是形如 {@code /reset} 的一律当作没识别出来，
     *       否则用户可以用话术诱导模型凭空触发系统动作；</li>
     *   <li>只认枚举里的名字（大小写/连字符宽松匹配），其余一律 UNKNOWN，不给模型自由发挥的空间。</li>
     * </ol>
     */
    public static Intent parse(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.startsWith("/") || s.startsWith("\\")) {
            return UNKNOWN;
        }
        String normalized = s.toUpperCase().replace('-', '_').replace(' ', '_');
        for (Intent i : values()) {
            if (i.name().equals(normalized)) {
                return i;
            }
        }
        return UNKNOWN;
    }
}
