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

    /** 找设备 / 问设备价格 */
    EQUIPMENT_QUERY("设备查询", "用户想买或想看设备，关心机型、价格、车源、表显小时数", Set.of(ToolCapability.EQUIPMENT),
            // 注意不要放"挖掘机""装载机"这类机型名词本身——它们在出租、求租、需求里都会出现，
            // 拿它当设备意图的高权重词会让"附近有挖掘机出租吗"被误判成设备查询
            Set.of("二手", "想买", "车源", "买挖掘机", "买装载机", "有没有卖"),
            Set.of("设备", "机器", "价格", "报价", "多少钱", "新机", "铲车", "买", "出售")),

    /** 设备对外出租（机主招租） */
    CHUZU_QUERY("出租查询", "用户想租设备，找机主发布的招租信息", Set.of(ToolCapability.CHUZU),
            Set.of("出租", "招租", "对外租"),
            Set.of("租金", "租", "租赁", "附近有", "可以租")),

    /** 求租（机主找活） */
    QIUZU_QUERY("求租查询", "机主找活干，问哪里有求租需求", Set.of(ToolCapability.QIUZU),
            Set.of("求租", "找活", "找机", "有活干", "谁要用"),
            Set.of("要租", "用工", "工期", "找台")),

    /** 用机需求 / 新机询价 */
    DEMAND_QUERY("需求询价", "用户想了解平台上的用机需求或新机询价线索", Set.of(ToolCapability.DEMAND),
            Set.of("需求", "求购", "询价", "用机需求", "谁要买"),
            Set.of("找人", "结款", "施工", "有哪些需求")),

    /** 平台资讯 / 文章 */
    NEWS_QUERY("资讯查询", "用户想看行业资讯、文章、评测", Set.of(ToolCapability.NEWS),
            Set.of("资讯", "新闻", "文章", "评测"),
            Set.of("行业动态", "保养", "维修", "最近有什么")),

    /** 平台规则 / FAQ / 流程 */
    POLICY_QUERY("平台规则", "用户问平台规则、流程、费用、押金、怎么操作", Set.of(ToolCapability.POLICY),
            Set.of("流程", "规则", "怎么收费", "手续费", "押金", "怎么退"),
            Set.of("规定", "政策", "怎么办", "如何", "平台怎么")),
    /** 发布出租 */
    PUBLISH_CHUZU("发布出租", "用户想把自己的设备发布成出租信息", Set.of(ToolCapability.PUBLISH),
            Set.of("帮我发布出租", "我要出租", "发布出租", "我要发布", "帮我挂"),
            Set.of("发布", "上架", "挂出去")),

    /** 发布求租 */
    PUBLISH_QIUZU("发布求租", "用户想发布一条求租或找机器信息", Set.of(ToolCapability.PUBLISH),
            Set.of("帮我发布求租", "我要发布求租", "发布求租", "我要找机器"),
            Set.of("发布需求", "发个求租")),

    /** 跨域综合：一次问多个域，需要多个 Agent 接力。关键词判定交给模型层。 */
    CROSS_DOMAIN("跨域综合", "一句话里同时问了两个及以上不同领域的问题，如既问价格又问流程", Set.of(ToolCapability.EQUIPMENT, ToolCapability.CHUZU,
            ToolCapability.QIUZU, ToolCapability.DEMAND, ToolCapability.NEWS),
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
