package com.jixiejia.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用角标写法归一化。纯函数，不依赖 Spring 与模型。
 *
 * <p>为什么要归一化：提示词只能"要求"模型用 [1] 这种写法，实际它会用
 * 【1】、［1］，甚至把资料前的 <1> 标签照抄回来。与其在渲染层认五六种写法，
 * 不如在服务端收敛成一种。
 */
class CitationSanitizerTest {

    @Test
    @DisplayName("全角方括号归一成半角")
    void normalizesFullWidthBrackets() {
        assertThat(CitationSanitizer.normalize("押金 7 天退【1】")).isEqualTo("押金 7 天退[1]");
        assertThat(CitationSanitizer.normalize("押金 7 天退［2］")).isEqualTo("押金 7 天退[2]");
    }

    @Test
    @DisplayName("资料在提示词里是 <1> 形态，模型照抄回来也要认")
    void normalizesAngleBrackets() {
        assertThat(CitationSanitizer.normalize("费率 3% <3>")).isEqualTo("费率 3% [3]");
    }

    @Test
    @DisplayName("括号里有空格也要认")
    void toleratesInnerSpaces() {
        assertThat(CitationSanitizer.normalize("服务费【 1 】")).isEqualTo("服务费[1]");
    }

    @Test
    @DisplayName("已经是半角方括号的原样保留")
    void leavesHalfWidthUntouched() {
        assertThat(CitationSanitizer.normalize("服务费 5% [1][2]")).isEqualTo("服务费 5% [1][2]");
    }

    @Test
    @DisplayName("没有角标的正文一个字都不能改")
    void leavesPlainTextUntouched() {
        String text = "这段回答里没有任何引用，也没有【异常】的括号。";
        assertThat(CitationSanitizer.normalize(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("null 与空串不炸")
    void handlesNullAndBlank() {
        assertThat(CitationSanitizer.normalize(null)).isNull();
        assertThat(CitationSanitizer.normalize("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("编号合法性：1..sourceCount 才算数")
    void validatesIndexRange() {
        assertThat(CitationSanitizer.isValid(1, 5)).isTrue();
        assertThat(CitationSanitizer.isValid(5, 5)).isTrue();
        assertThat(CitationSanitizer.isValid(0, 5)).isFalse();
        assertThat(CitationSanitizer.isValid(6, 5)).isFalse();
        assertThat(CitationSanitizer.isValid(1, 0)).isFalse();
    }
}
