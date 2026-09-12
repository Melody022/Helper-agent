package com.jixiejia.agent.eval;

import com.jixiejia.agent.classify.ClassifyLayer;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.classify.IntentClassifier;
import com.jixiejia.agent.classify.IntentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图识别准确率评估。
 *
 * <p><b>为什么要有这个。</b>"准确率 95%" 这种数字，如果只是自己感觉"还行"，
 * 面试官追问一句"怎么测的、多少条样本、错在哪"就答不上来了。这里用一份人工标注的
 * 评估集真跑一遍，把准确率、各层命中分布、每个意图的准确率都算出来——
 * 数字是自己测的，错在哪也一目了然。
 *
 * <p>默认<b>不参与</b>常规测试：每次要调几十次模型，跑一遍要一两分钟，
 * 混在 CI 里会让每次提交都变慢。需要时手动跑：
 * <pre>
 *   mvn test -Dtest=IntentEvalTest -Deval.enabled=true
 * </pre>
 *
 * <p>注意评估集故意包含了容易错的说法（口语化、隐含意图、跨域），
 * 全用标准句式测出来的高准确率没有意义。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "eval.enabled", matches = "true")
class IntentEvalTest {

    /** 一条评估样本 */
    private record Sample(String question, Intent expected) {
    }

    /**
     * 人工标注的评估集。
     *
     * <p>构成：标准说法、口语说法、隐含意图、跨域各占一部分。
     * 期望值按"**用户真正想要什么**"标注，不按字面关键词。
     *
     * <p><b>为什么按"对照组"组织而不是按意图堆数量。</b>
     * 只有"每个意图各来几条"的话，改完看不出是真好了还是换了个错法。
     * 真正会暴露问题的是**长得像但期望不同**的句子——
     * 所以下面专门有几组对照：发布 vs 查询、跨域 vs 不该跨域。
     * 词表/判据一动，先看这几组掉没掉。
     */
    private static final List<Sample> SAMPLES = List.of(
            // ================= 设备买卖 =================
            new Sample("有没有二手的挖掘机", Intent.EQUIPMENT_QUERY),
            new Sample("小松200卖多少钱", Intent.EQUIPMENT_QUERY),
            new Sample("想买台装载机，有什么车源", Intent.EQUIPMENT_QUERY),
            new Sample("这台挖掘机表显多少小时", Intent.EQUIPMENT_QUERY),
            new Sample("有卡特320吗", Intent.EQUIPMENT_QUERY),
            new Sample("20吨级挖机什么价", Intent.EQUIPMENT_QUERY),
            new Sample("有没有三一的车源", Intent.EQUIPMENT_QUERY),

            // ================= 出租查询（我是承租方）=================
            new Sample("附近有挖掘机出租吗", Intent.CHUZU_QUERY),
            new Sample("我想租台挖机干活", Intent.CHUZU_QUERY),
            new Sample("你们这儿有对外租的设备吗", Intent.CHUZU_QUERY),
            new Sample("有没有洋马60出租", Intent.CHUZU_QUERY),
            new Sample("挖掘机出租一天多少钱", Intent.CHUZU_QUERY),
            new Sample("6吨以下的小挖好租吗", Intent.CHUZU_QUERY),

            // ================= 求租（"有人要租设备"：机主看这些找活）=================
            new Sample("哪里有求租的", Intent.QIUZU_QUERY),
            new Sample("我有台挖机想找活干", Intent.QIUZU_QUERY),
            new Sample("工地上有没有活干", Intent.QIUZU_QUERY),
            new Sample("想给机器找个长期活", Intent.QIUZU_QUERY),

            // ================= 求租（含原来的"用机需求"）=================
            // "需求"和"求租"在平台上是同一个东西（jxb_qiuzu 装的就是"要租机器的人发的需求"），
            // 原来那个 DEMAND_QUERY 意图已经去掉了——两个近义意图并存正是句子被判歪的原因。
            new Sample("现在有哪些设备需求", Intent.QIUZU_QUERY),
            new Sample("最近有哪些用机需求", Intent.QIUZU_QUERY),
            // 新机询价不是同一回事（那是"有人想买"），归到设备买卖那边
            new Sample("平台上有人要买新机吗", Intent.EQUIPMENT_QUERY),

            // ================= 平台知识（规则 + 维修保养）=================
            new Sample("在平台租设备的流程是什么", Intent.KNOWLEDGE_QUERY),
            new Sample("押金怎么退", Intent.KNOWLEDGE_QUERY),
            new Sample("小松200-8空调故障怎么解决", Intent.KNOWLEDGE_QUERY),
            new Sample("挖掘机多久保养一次", Intent.KNOWLEDGE_QUERY),
            new Sample("平台收多少服务费", Intent.KNOWLEDGE_QUERY),
            new Sample("发布设备要审核多久", Intent.KNOWLEDGE_QUERY),
            new Sample("设备过户需要什么材料", Intent.KNOWLEDGE_QUERY),
            new Sample("挖掘机液压油多久换一次", Intent.KNOWLEDGE_QUERY),
            new Sample("装载机冒黑烟是什么原因", Intent.KNOWLEDGE_QUERY),
            new Sample("押金多久能退回来", Intent.KNOWLEDGE_QUERY),

            // ================= 资讯列表 =================
            new Sample("有什么挖掘机相关的资讯", Intent.NEWS_QUERY),
            new Sample("最近有什么文章", Intent.NEWS_QUERY),
            new Sample("最近有什么行业动态", Intent.NEWS_QUERY),
            new Sample("有没有挖掘机的评测文章", Intent.NEWS_QUERY),

            // ================= 发布（我是机主，要挂出去）=================
            new Sample("帮我发布一台出租", Intent.PUBLISH_CHUZU),
            new Sample("我要出租一台装载机", Intent.PUBLISH_CHUZU),
            new Sample("我想出租一台洋马60", Intent.PUBLISH_CHUZU),
            new Sample("我打算把设备放平台出租", Intent.PUBLISH_CHUZU),
            new Sample("我有台挖机想挂出去", Intent.PUBLISH_CHUZU),
            new Sample("我要发布求租信息", Intent.PUBLISH_QIUZU),
            new Sample("帮我发个求租，找一台20吨挖机", Intent.PUBLISH_QIUZU),

            // ================= 短路：转人工 / 投诉 / 闲聊 =================
            new Sample("转人工", Intent.HANDOFF),
            new Sample("我要找客服", Intent.HANDOFF),
            new Sample("我要投诉", Intent.COMPLAINT),
            new Sample("你们这是骗人的吧", Intent.COMPLAINT),
            new Sample("我要举报一个卖家", Intent.COMPLAINT),
            new Sample("你好", Intent.CHITCHAT),
            new Sample("谢谢", Intent.CHITCHAT),

            // ================= 跨域：确实问了两个及以上领域 =================
            new Sample("有没有20吨挖机、附近能租吗、流程是啥", Intent.CROSS_DOMAIN),
            new Sample("有二手的挖掘机吗，另外有没有挖掘机出租", Intent.CROSS_DOMAIN),
            new Sample("想买台挖机，也想知道有没有出租的", Intent.CROSS_DOMAIN),
            new Sample("有没有车源，另外有没有活干", Intent.CROSS_DOMAIN),

            // ============ 对照组一：发布 vs 查询 ============
            // 这几对的**字面高度重合**（都含"出租"），但**主体不同**——
            // "我出租我的设备"是发布，"我找别人出租的设备"是查询。
            // 词表一放宽或一收紧，先看这一组：
            //
            //   发布侧："我想出租一台洋马60" / "我有台挖机想挂出去"
            //   查询侧："有没有洋马60出租" / "挖掘机出租一天多少钱"
            //
            // 用户实测报过的就是发布侧第一句——当时被判成了查询。
            // （上面已按意图分组收录，这里不再重复，避免同一句被测两次拉高权重。）

            // ============ 对照组二：字面命中多个域，但**不该**判跨域 ============
            // 这几句里都出现了两个领域的词，但用户其实只问了一件事。
            // "数关键词命中几个域"这个判据最容易在这里翻车。
            // （已在上面出现过的样本不在这里重复，避免同一句被测两次拉高权重。）
            new Sample("二手设备出租信息多吗", Intent.CHUZU_QUERY),
            new Sample("有没有带属具的挖机出租", Intent.CHUZU_QUERY),
            new Sample("装载机出租的行情怎么样", Intent.CHUZU_QUERY),
            new Sample("二手挖机好出手吗", Intent.EQUIPMENT_QUERY),

            // ================= 口语化 / 隐含意图 =================
            new Sample("我这边有台小松200想放出去赚点租金", Intent.PUBLISH_CHUZU),
            new Sample("手头有台多余的设备，闲着也是闲着", Intent.PUBLISH_CHUZU),
            new Sample("工地马上开工了还差两台挖机", Intent.QIUZU_QUERY),
            new Sample("你们这个钱怎么结", Intent.KNOWLEDGE_QUERY),
            new Sample("这台车还能值多少", Intent.EQUIPMENT_QUERY),
            new Sample("挖机空调不制冷了", Intent.KNOWLEDGE_QUERY),
            new Sample("想弄台小挖，预算不多", Intent.EQUIPMENT_QUERY)
    );

    @Autowired
    private IntentClassifier intentClassifier;

    @Test
    @DisplayName("意图识别评估：跑一遍标注集，输出准确率与逐类明细")
    void evaluateIntentAccuracy() {
        int correct = 0;
        Map<ClassifyLayer, Integer> layerHits = new EnumMap<>(ClassifyLayer.class);
        Map<Intent, int[]> perIntent = new LinkedHashMap<>();   // [命中, 总数]
        List<String> errors = new ArrayList<>();

        for (Sample sample : SAMPLES) {
            IntentResult result = intentClassifier.classify(sample.question(), null);

            perIntent.computeIfAbsent(sample.expected(), k -> new int[2])[1]++;
            layerHits.merge(result.layer(), 1, Integer::sum);

            if (result.intent() == sample.expected()) {
                correct++;
                perIntent.get(sample.expected())[0]++;
            } else {
                errors.add(String.format("  %-30s 期望 %-16s 实际 %-16s (%.2f/%s)",
                        sample.question(), sample.expected(), result.intent(),
                        result.confidence(), result.layer().code()));
            }
        }

        printReport(correct, layerHits, perIntent, errors);

        double accuracy = (double) correct / SAMPLES.size();
        // 设一个下限而不是精确值：模型有波动，精确断言会让评估变成抽奖；
        // 但低于这个线说明识别质量真的退化了，必须让测试红掉
        assertThat(accuracy)
                .as("意图识别准确率不应低于 80%%")
                .isGreaterThanOrEqualTo(0.80);
    }

    private void printReport(int correct, Map<ClassifyLayer, Integer> layerHits,
                             Map<Intent, int[]> perIntent, List<String> errors) {
        int total = SAMPLES.size();
        StringBuilder sb = new StringBuilder();
        sb.append("\n================ 意图识别评估报告 ================\n");
        sb.append(String.format("样本数：%d    正确：%d    准确率：%.1f%%%n",
                total, correct, 100.0 * correct / total));

        sb.append("\n---- 各分类层命中分布（看成本结构：越靠前的层越便宜）----\n");
        for (Map.Entry<ClassifyLayer, Integer> e : layerHits.entrySet()) {
            sb.append(String.format("  %-10s %3d 条  (%.0f%%)%n",
                    e.getKey().code(), e.getValue(), 100.0 * e.getValue() / total));
        }

        sb.append("\n---- 各意图准确率 ----\n");
        perIntent.forEach((intent, stat) -> sb.append(String.format(
                "  %-16s %2d/%2d  %s%n", intent,
                stat[0], stat[1], stat[0] == stat[1] ? "" : "  <-- 有错判")));

        if (!errors.isEmpty()) {
            sb.append("\n---- 错判明细（这些就是可以拿去讲的 case）----\n");
            errors.forEach(e -> sb.append(e).append('\n'));
        }
        sb.append("=================================================\n");

        System.out.println(sb);
    }
}
