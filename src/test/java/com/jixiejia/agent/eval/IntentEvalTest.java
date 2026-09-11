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
     * 期望值按"用户真正想要什么"标注，不按字面关键词。
     */
    private static final List<Sample> SAMPLES = List.of(
            // ---- 设备买卖 ----
            new Sample("有没有二手的挖掘机", Intent.EQUIPMENT_QUERY),
            new Sample("小松200卖多少钱", Intent.EQUIPMENT_QUERY),
            new Sample("想买台装载机，有什么车源", Intent.EQUIPMENT_QUERY),
            new Sample("这台挖掘机表显多少小时", Intent.EQUIPMENT_QUERY),

            // ---- 出租 ----
            new Sample("附近有挖掘机出租吗", Intent.CHUZU_QUERY),
            new Sample("我想租台挖机干活", Intent.CHUZU_QUERY),
            new Sample("你们这儿有对外租的设备吗", Intent.CHUZU_QUERY),

            // ---- 求租（机主找活）----
            new Sample("哪里有求租的", Intent.QIUZU_QUERY),
            new Sample("我有台挖机想找活干", Intent.QIUZU_QUERY),

            // ---- 用机需求 / 询价 ----
            new Sample("现在有哪些设备需求", Intent.DEMAND_QUERY),
            new Sample("平台上有人要买新机吗", Intent.DEMAND_QUERY),

            // ---- 平台知识（规则 + 维修保养）----
            new Sample("在平台租设备的流程是什么", Intent.KNOWLEDGE_QUERY),
            new Sample("押金怎么退", Intent.KNOWLEDGE_QUERY),
            new Sample("小松200-8空调故障怎么解决", Intent.KNOWLEDGE_QUERY),
            new Sample("挖掘机多久保养一次", Intent.KNOWLEDGE_QUERY),

            // ---- 资讯列表 ----
            new Sample("有什么挖掘机相关的资讯", Intent.NEWS_QUERY),
            new Sample("最近有什么文章", Intent.NEWS_QUERY),

            // ---- 发布 ----
            new Sample("帮我发布一台出租", Intent.PUBLISH_CHUZU),
            new Sample("我要发布求租信息", Intent.PUBLISH_QIUZU),

            // ---- 短路 ----
            new Sample("转人工", Intent.HANDOFF),
            new Sample("我要投诉", Intent.COMPLAINT),

            // ---- 闲聊 / 兜底 ----
            new Sample("你好", Intent.CHITCHAT),

            // ---- 跨域 ----
            new Sample("有二手的挖掘机吗，另外有没有挖掘机出租", Intent.CROSS_DOMAIN),

            // ---- 容易错的：口语化、隐含意图 ----
            new Sample("我这边有台小松200想放出去赚点租金", Intent.CHUZU_QUERY),
            new Sample("工地马上开工了还差两台挖机", Intent.DEMAND_QUERY),
            new Sample("你们这个钱怎么结", Intent.KNOWLEDGE_QUERY)
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
