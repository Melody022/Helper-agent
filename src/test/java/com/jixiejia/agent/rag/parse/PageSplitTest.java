package com.jixiejia.agent.rag.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按分页符切页。纯函数，不依赖 Spring 与外部命令。
 *
 * <p>单独一个测试类是因为 {@link DocumentParser#splitPages} 是**包级可见**的
 * （跨包够不着），而它值得直接单测——这条路径的兜底分支出过一个"看起来兜住了、
 * 其实把 OCR 结果全丢了"的 bug，跑真实文档很难稳定复现，纯函数一测就露。
 */
class PageSplitTest {

    @Test
    @DisplayName("分页符个数和页数对得上时，按页切开")
    void splitsOnSeparators() {
        List<String> warnings = new ArrayList<>();

        List<String> pages = DocumentParser.splitPages("第一页正文\n-----\n第二页正文", 2, warnings);

        assertThat(warnings).as("对得上就不该告警").isEmpty();
        assertThat(pages).containsExactly("第一页正文", "第二页正文");
    }

    @Test
    @DisplayName("分页符对不上时整篇当一页，但返回的列表**必须可变**")
    void mismatchedSeparatorsFallBackToMutableList() {
        List<String> warnings = new ArrayList<>();

        // 只有 1 个分页符，却说有 5 页 → 对不上，走兜底
        List<String> pages = DocumentParser.splitPages("第一页正文\n-----\n第二页正文", 5, warnings);

        assertThat(warnings).as("兜底要留下告警").isNotEmpty();
        assertThat(pages).hasSize(1);

        // ⚠️ 这条断言才是重点：applyOcr 会把 OCR 结果**写回**这个列表。
        // 兜底分支原来返回 List.of(...)——不可变，set() 会抛
        // UnsupportedOperationException，被外层 catch 兜住 → 整篇 OCR 的文字全丢，
        // 只留一条"本次不做分页处理（跨页表格可能无法合并）"的 warning，
        // 读起来像只丢了个小功能。
        pages.set(0, "OCR 重写后的内容");
        assertThat(pages.get(0)).isEqualTo("OCR 重写后的内容");
    }

    @Test
    @DisplayName("没有分页符（单页扫描件）也必须是可变的")
    void singlePageFallBackIsMutable() {
        List<String> pages = DocumentParser.splitPages("只有一页正文，没有任何分页符", 1, new ArrayList<>());

        assertThat(pages).hasSize(1);

        // 单页扫描件必然走这条兜底，所以这里是**最容易中招**的一条：
        // 传一张单页扫描的 PDF，OCR 一定失败。
        pages.set(0, "OCR 重写后的内容");
        assertThat(pages.get(0)).isEqualTo("OCR 重写后的内容");
    }

    @Test
    @DisplayName("分页符两边的空行不影响切分")
    void toleratesSurroundingWhitespace() {
        List<String> pages = DocumentParser.splitPages("上页\n  -----  \n下页", 2, new ArrayList<>());

        assertThat(pages).containsExactly("上页", "下页");
    }
}
