package com.jixiejia.agent.classify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 第 1 层：关键词加权分类。
 *
 * <p>每个意图取"命中的最高权重关键词"作为得分，最高分达到高权重阈值（默认 0.9）
 * <b>且严格高于第二名</b>时才定案。
 *
 * <p>为什么要卡"严格高于第二名"：像"挖掘机出租"这种句子会同时命中设备意图和出租意图。
 * 只看最高分就直出的话，等于用关键词的枚举顺序决定路由，用户会被随机送进 EquipmentAgent
 * 或 RentalAgent。这种歧义应该下沉给模型层判断，而不是在规则层硬猜。
 *
 * <p>这一层的价值不只是省钱：它完全可复现、可回归，冒烟测试里"关键词高置信直出"靠的就是它。
 */
@Slf4j
@Component
public class KeywordWeightClassifier {

    private final double highWeight;
    private final double midWeight;

    public KeywordWeightClassifier(
            @Value("${routing.keyword.high-weight:0.9}") double highWeight,
            @Value("${routing.keyword.mid-weight:0.7}") double midWeight) {
        this.highWeight = highWeight;
        this.midWeight = midWeight;
    }

    /** 打分结果：按得分降序排列的意图，以及各自命中的词。 */
    private record Scored(List<Map.Entry<Intent, Double>> ranked, Map<Intent, List<String>> hits) {
    }

    /**
     * "我要把我的设备租出去"的意愿词。
     *
     * <p>和 {@link #PUBLISH_ACTIONS} 是**同现**关系，不要求相邻——
     * 因为"我打算把设备放平台出租"中间隔了六个字，要求相邻会漏。
     */
    private static final List<String> PUBLISH_WILLS =
            List.of("想", "要", "打算", "准备", "帮我", "请帮我");

    /**
     * 表达"**把东西放出去**"这个动作的词。
     *
     * <p>注意两处刻意的取舍：
     * <ul>
     *   <li><b>只收"出租/租出去"，不收"租"</b>——"我想租台挖机"里的"租"是承租，不是发布。</li>
     *   <li><b>不收"求租"本身</b>——"求租"是需求类型，不是发布动作。
     *       "我想求租"是承租方想找机器（该走出租查询），不是要发布什么东西。
     *       真正表示发布的是"**发**个求租"里那个"发"，所以收的是"发个求租/发条求租"。</li>
     * </ul>
     */
    private static final List<String> PUBLISH_ACTIONS =
            List.of("出租", "租出去", "挂出去", "放出去", "发布", "发个求租", "发条求租", "发求租");

    /**
     * 这句话像不像在**表达发布意愿**。
     *
     * <p><b>为什么单独判这个，而不是继续往词表里加词。</b>
     * 发布意图的表达方式是**开放集合**——"我想出租""我要出租""我打算把设备放平台出租"
     * "帮我发个求租""我有台挖机想挂出去"…用一个词表枚举整句永远会漏，
     * 而每漏一句，这句话就会被"出租""求租"这些**查询**领域词抢走。
     * 评估集实测：发布意图 9 条错 4 条，其中 3 条是关键词层 **0.90 高置信直出**的。
     *
     * <p><b>它只"让路"，不"定案"。</b>命中时关键词层直接放弃定案（返回 empty），
     * 把决定交给模型——所以这条规则**最坏只是多跑一次本地小模型（免费）**，
     * 不会导致新的错判。这跟"用词表判定案"有本质区别：后者猜错就是错的，
     * 前者猜多了只是慢一点。
     *
     * <p>不会误伤查询侧：
     * <ul>
     *   <li>"附近有挖掘机出租吗"——没有意愿词；</li>
     *   <li>"我想租台挖机干活"——"租"不在动作词里（只收"出租/租出去"）；</li>
     *   <li>"有没有洋马60出租"——没有意愿词。</li>
     * </ul>
     */
    static boolean looksLikePublishIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean hasWill = PUBLISH_WILLS.stream().anyMatch(text::contains);
        if (!hasWill) {
            return false;
        }
        return PUBLISH_ACTIONS.stream().anyMatch(text::contains);
    }

    private Scored score(String text) {
        Map<Intent, Double> scores = new LinkedHashMap<>();
        Map<Intent, List<String>> hits = new LinkedHashMap<>();

        for (Intent intent : Intent.values()) {
            if (intent == Intent.UNKNOWN || intent == Intent.CROSS_DOMAIN) {
                continue;
            }
            double best = 0;
            List<String> matched = new ArrayList<>();

            for (String kw : intent.highKeywords()) {
                if (text.contains(kw)) {
                    best = Math.max(best, highWeight);
                    matched.add(kw);
                }
            }
            for (String kw : intent.midKeywords()) {
                if (text.contains(kw)) {
                    best = Math.max(best, midWeight);
                    matched.add(kw);
                }
            }
            if (best > 0) {
                scores.put(intent, best);
                hits.put(intent, matched);
            }
        }

        List<Map.Entry<Intent, Double>> ranked = scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Intent, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .toList();
        return new Scored(ranked, hits);
    }

    /**
     * 尝试用关键词定案。
     *
     * @return 定案时返回结果，否则 empty（交给下一层）
     */
    public Optional<IntentResult> classify(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Scored scored = score(text);
        if (scored.ranked().isEmpty()) {
            return Optional.empty();
        }

        Map.Entry<Intent, Double> top = scored.ranked().get(0);
        double runnerUp = scored.ranked().size() > 1 ? scored.ranked().get(1).getValue() : 0.0;
        String hitText = String.join("、", scored.hits().get(top.getKey()));

        // 动作意图（发布）优先于查询意图：用户说"帮我发布一台出租"要的是发布流程，
        // 不该被"出租"这个词抢到出租查询上。实测评估里就是这么错判的。
        //
        // 注意这一条仍然保留：它只在**命中了发布词表里的词**时才生效（"帮我发布""我要出租"…），
        // 那些词几乎唯一指向发布，判它是对的、而且快。
        Optional<Intent> action = dominantActionIntent(scored);
        if (action.isPresent()) {
            String actionHits = String.join("、", scored.hits().getOrDefault(action.get(), List.of()));
            log.debug("关键词层定案（动作意图优先）：{} 命中 [{}]", action.get(), actionHits);
            return Optional.of(IntentResult.of(action.get(), highWeight,
                    ClassifyLayer.KEYWORD, "命中发布意图关键词：" + actionHits));
        }

        // 有发布意愿但没命中发布词表 —— **让路，别抢答**。
        //
        // "我想出租一台洋马60"这句话里只有"出租"命中，而"出租"属于出租查询。
        // 词表分不清"我出租我的设备"和"我找别人出租的设备"，硬判就是 0.90 高置信判错。
        // 所以这里直接放弃定案，交给模型——它能同时看到两种意图的说明，比词表会分。
        // 代价只是多跑一次本地小模型（免费）；换来的是不会再把发布判成查询。
        if (looksLikePublishIntent(text)) {
            log.debug("有发布意愿但未命中发布词表，关键词层让路交给模型：{}", text);
            return Optional.empty();
        }

        if (top.getValue() >= highWeight && top.getValue() > runnerUp) {
            log.debug("关键词层定案：{} 命中 [{}]", top.getKey(), hitText);
            return Optional.of(IntentResult.of(top.getKey(), top.getValue(),
                    ClassifyLayer.KEYWORD, "命中关键词：" + hitText));
        }

        log.debug("关键词层不足以定案：最高 {}={}，次高={}", top.getKey(), top.getValue(), runnerUp);
        return Optional.empty();
    }

    /**
     * 关键词层给出的倾向，不论分数够不够。
     * 只作为提示传给模型层——是"参考"不是"约束"，模型可以推翻。
     *
     * <p><b>有发布意愿时，提示的是"发布"，而不是不理它。</b>
     *
     * <p>这里修过两次，第一次的教训值得记：
     * <ul>
     *   <li><b>一开始</b>：让关键词层直接判"出租查询"——0.90 高置信判错；</li>
     *   <li><b>然后改成"让路"</b>（不给任何提示）——实测发现 7B 小模型自己
     *       也分不清"出租查询"和"发布出租"，"我想出租一台洋马60"照样被判成查询。
     *       原因是这句话里只有"出租"这个词，模型缺少"主体是机主"的证据；</li>
     *   <li><b>现在</b>：把已经识别出来的"发布意愿"**当提示告诉模型**
     *       （"规则层注意到：这句话像是在表达把自己的设备租出去的意愿"）。</li>
     * </ul>
     *
     * <p>关键区别：**提示是建议，定案是结论**。提示错了模型能推翻，
     * 定案错了就是错的。所以同一条判据，当提示用是安全的，当判据用是危险的。
     *
     * <p>不给提示时（其余情况）仍然沿用"分数最高的那个"，见方法后半段。
     */
    public Optional<Intent> hint(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (looksLikePublishIntent(text)) {
            // "求租"归类到发布求租，其余（出租/挂出去/放出去）归类到发布出租
            return Optional.of(text.contains("求租")
                    ? Intent.PUBLISH_QIUZU : Intent.PUBLISH_CHUZU);
        }
        Scored scored = score(text);
        return scored.ranked().isEmpty()
                ? Optional.empty()
                : Optional.of(scored.ranked().get(0).getKey());
    }

    /**
     * 只列高权重命中的意图，按得分降序。
     * 跨域判定专用：阈值就取本类的高权重，避免调用方再抄一份数字。
     */
    public List<Intent> highWeightIntents(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Scored scored = score(text);

        // 动作意图一旦命中就独占，不再算跨域：
        // 否则"帮我发布一台出租"会同时命中发布和出租，被判成跨域、
        // 触发一次毫无意义的综合（用户要的是发布流程，不是把两边的查询结果拼起来）
        Optional<Intent> action = dominantActionIntent(scored);
        if (action.isPresent()) {
            return List.of(action.get());
        }

        return scored.ranked().stream()
                .filter(e -> e.getValue() >= highWeight)
                .filter(e -> e.getKey() != Intent.UNKNOWN && e.getKey() != Intent.CROSS_DOMAIN)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 找出已定案的动作型意图（发布类）。
     *
     * <p>两个发布意图可能同时命中（"我要发布求租"既含"我要发布"也含"发布求租"），
     * 这时取<b>匹配词更长</b>的那个——"发布求租"比笼统的"发布"更具体，更可信。
     */
    private Optional<Intent> dominantActionIntent(Scored scored) {
        return scored.ranked().stream()
                .filter(e -> e.getKey().isActionIntent() && e.getValue() >= highWeight)
                .max(Comparator.comparingInt(e -> longestHit(scored, e.getKey())))
                .map(Map.Entry::getKey);
    }

    private static int longestHit(Scored scored, Intent intent) {
        return scored.hits().getOrDefault(intent, List.of()).stream()
                .mapToInt(String::length)
                .max()
                .orElse(0);
    }
}
