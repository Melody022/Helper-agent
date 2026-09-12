package com.jixiejia.agent.classify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "这句话像不像在表达发布意愿"——纯函数，不依赖 Spring 与模型。
 *
 * <p>单独测它是因为这条判据顶着两个"必须同时成立"的要求：
 * <ul>
 *   <li><b>要认得出各种说法</b>：发布意图的表达是开放集合
 *       （"我想出租""我打算把设备放平台出租""帮我发个求租"…），
 *       靠词表枚举整句永远会漏，漏掉的每一句都会被"出租""求租"这些查询词抢走；</li>
 *   <li><b>又不能误伤查询侧</b>：查询侧的说法和它高度重合——都含"出租"或"求租"。</li>
 * </ul>
 * 下面两组用例就是钉这两个边界，改动时先看它们。
 */
class PublishIntentHintTest {

    // ---------------- 该认出来的：我是机主，要把设备挂出去 ----------------

    @Test
    @DisplayName("认得出各种发布说法（这是评估集里实际错判过的那些）")
    void detectsPublishExpressions() {
        // 用户实测报回来的原句
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("我想出租一台洋马60")).isTrue();
        // 意愿词和动作词中间隔了六个字——所以判据不能要求相邻
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("我打算把设备放平台出租")).isTrue();
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("我有台挖机想挂出去")).isTrue();
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("帮我发个求租，找一台20吨挖机")).isTrue();
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("我这边有台小松200想放出去赚点租金")).isTrue();
    }

    // ---------------- 不能误伤的：我是承租方，要找机器 ----------------

    @Test
    @DisplayName("不误伤查询侧——这些句子也该含'出租'或'求租'，但主体不是机主")
    void doesNotFireOnQuerySide() {
        // 没有意愿词，只是问有没有
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("附近有挖掘机出租吗")).isFalse();
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("有没有洋马60出租")).isFalse();
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("挖掘机出租一天多少钱")).isFalse();

        // "租"是承租，不是"出租"——动作词表刻意不收单字"租"
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("我想租台挖机干活")).isFalse();

        // ⚠️ "求租"是需求类型，不是发布动作。
        // "我想求租"是承租方想找机器（该走出租查询），不是要发布什么。
        // 这条一开始判错了，还让两个路由测试红了——见 KeywordWeightClassifier 里的说明。
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("我想求租")).isFalse();
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("哪里有求租的")).isFalse();
    }

    @Test
    @DisplayName("只满足一半（有意愿没动作 / 有动作没意愿）都不算")
    void needsBothHalves() {
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("我想想")).isFalse();
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("出租")).isFalse();
        assertThat(KeywordWeightClassifier.looksLikePublishIntent(null)).isFalse();
        assertThat(KeywordWeightClassifier.looksLikePublishIntent("   ")).isFalse();
    }
}
