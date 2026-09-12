package com.jixiejia.agent.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiKnowledgeDoc;
import com.jixiejia.agent.persistence.mapper.ai.AiKnowledgeDocMapper;
import com.jixiejia.agent.rag.parse.ParsedDocument;
import com.jixiejia.agent.rag.parse.ParsedTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入库的批处理上限回归测试。
 *
 * <p><b>守的是一个潜伏很久、被上传功能才逼出来的 bug。</b>
 * DashScope 的 embedding 接口单次最多接受 10 条文本，而代码里原本写的是 16。
 * 这个错配一直没暴露，因为以前入库的都是零散短文本（内置规则每条 1~2 个切片、
 * 平台文章正文大多是空的），从来凑不满一批。直到 M9 支持上传整份文档——
 * 一个几千字的文档就有几十个切片，第一批就把接口打爆：
 *
 * <pre>
 * 400: batch size is invalid, it should not be larger than 10.: input.contents
 * </pre>
 *
 * <p>失败后的表现还很会骗人：文档停在 {@code PARSED}、切片数为 0、正文却已经存进去了，
 * 从管理页只看得出"上传转了半天然后没了"，完全看不出是批量上限。
 *
 * <p>所以这条用例刻意造一份<b>切片数远超一批</b>的文档（正文 + 4 张表），
 * 只要批次上限再被改大就会红。
 */
@SpringBootTest
class KnowledgeIngestBatchTest {

    @Autowired
    private KnowledgeIngestionService ingestionService;

    @Autowired
    private AiKnowledgeDocMapper docMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("入库：切片数远超 embedding 批上限时，仍然完整入库")
    void ingestsDocumentWithManyChunks() throws Exception {
        Assumptions.assumeTrue(true, "需要 ES 与 embedding 服务");

        String sourceId = "test-batch-" + UUID.randomUUID();

        // 正文长度刻意造到"切片数 > 一批"的量级
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("第").append(i).append("条 试验方法与判定准则，本条款规定了设备的检验要求。\n");
        }
        String text = sb.toString();

        // 再加几张表（表格摘要也会各占一个切片）
        List<ParsedTable> tables = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r < 30; r++) {
                rows.add(List.of("项" + r, "值" + r, "单位" + r));
            }
            tables.add(new ParsedTable(t, 1, 2, "表" + t, List.of("项目", "取值", "单位"), rows, ""));
        }

        try {
            int chunks = ingestionService.ingestUpload(sourceId, "批上限回归用例",
                    new ParsedDocument(text, "批上限回归用例", 15, 15, tables, List.of()));

            assertThat(chunks)
                    .as("切片数应当远超一批（%d），否则这条用例守不住批量上限", 10)
                    .isGreaterThan(10);

            AiKnowledgeDoc doc = docMapper.selectOne(Wrappers.<AiKnowledgeDoc>lambdaQuery()
                    .eq(AiKnowledgeDoc::getSourceId, sourceId));
            assertThat(doc).isNotNull();
            assertThat(doc.getStatus())
                    .as("入库后状态必须是 INDEXED；停在 PARSED 说明向量化那步挂了")
                    .isEqualTo("INDEXED");
            assertThat(doc.getChunkCount()).isEqualTo(chunks);
            assertThat(doc.getErrorMsg()).isNull();

        } finally {
            // 物理删除：doc 带 @TableLogic，走 mapper 只会把 del_flag 置 2，行会留在表里
            jdbc.update("DELETE FROM ai_knowledge_chunk WHERE doc_id IN "
                    + "(SELECT id FROM ai_knowledge_doc WHERE source_id = ?)", sourceId);
            jdbc.update("DELETE FROM ai_knowledge_table WHERE doc_id IN "
                    + "(SELECT id FROM ai_knowledge_doc WHERE source_id = ?)", sourceId);
            jdbc.update("DELETE FROM ai_knowledge_doc WHERE source_id = ?", sourceId);
        }
    }
}
