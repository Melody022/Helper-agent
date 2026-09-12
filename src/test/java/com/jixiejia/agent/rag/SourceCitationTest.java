package com.jixiejia.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 溯源列表要带够前端定位文件所需的信息。
 *
 * <p>为什么值得单独测：`Source` 少一个 `docId`，前端就不知道点开该打开哪份文件——
 * 而这个缺失**不会有任何报错**。上一轮就出过一次同类问题：
 * `expandTables` 换个构造参数把 sectionPath/pageNo 丢成 null，
 * 表现只是"溯源列表里没有出处"（踩坑第 42 条）。
 */
class SourceCitationTest {

    private static KnowledgeRetriever.Hit hit(Long docId) {
        return new KnowledgeRetriever.Hit("v-" + docId, docId, 1L, "标题", "正文",
                0.1, 0.2, 0.3, 1, 1, null, "5 安全要求", 11);
    }

    @Test
    @DisplayName("溯源项带上 docId，前端才知道该打开哪份文件")
    void carriesDocId() {
        List<KnowledgeAnswerService.Source> sources =
                KnowledgeAnswerService.toSources(List.of(hit(7L)), Map.of());

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).docId()).isEqualTo(7L);
        assertThat(sources.get(0).sectionPath()).isEqualTo("5 安全要求");
        assertThat(sources.get(0).pageNo()).isEqualTo(11);
    }

    @Test
    @DisplayName("没有原件来源的（文章/飞轮）标记为不可预览")
    void marksNonUploadSourcesUnpreviewable() {
        List<KnowledgeAnswerService.Source> sources =
                KnowledgeAnswerService.toSources(List.of(hit(7L)), Map.of());

        assertThat(sources.get(0).previewable()).isFalse();
    }
}
