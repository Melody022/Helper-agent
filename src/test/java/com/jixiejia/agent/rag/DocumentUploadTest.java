package com.jixiejia.agent.rag;

import com.jixiejia.agent.rag.parse.DocBlock;
import com.jixiejia.agent.rag.parse.DocumentParseException;
import com.jixiejia.agent.rag.parse.DocumentParser;
import com.jixiejia.agent.rag.parse.MarkdownTableExtractor;
import com.jixiejia.agent.rag.parse.ParsedDocument;
import com.jixiejia.agent.rag.parse.ParsedTable;
import com.jixiejia.agent.rag.parse.SectionTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文档解析与表格抽取。
 *
 * <p>分两类：
 * <ul>
 *   <li><b>纯逻辑</b>——表格识别与校验（含把公式假表格挡掉），不依赖任何外部进程；</li>
 *   <li><b>真解析</b>——拿真实的国标 PDF 跑一遍 LiteParse，标了
 *       {@code -Dparse.eval=true} 才跑（要起进程、要网络、慢）。</li>
 * </ul>
 */
class DocumentUploadTest {

    // ---------------- 纯逻辑：表格识别 ----------------

    @Test
    @DisplayName("表格识别：正常的 markdown 表能认出来")
    void extractsRealTable() {
        List<String> lines = List.of(
                "6.1.2 试验条件",
                "| 测量参数 | 准确度 |",
                "|---|---|",
                "| 电压/V | ±1% |",
                "| 电流/A | ±1% |",
                "| 电能消耗量/kW·h | ±2% |");

        List<MarkdownTableExtractor.Block> blocks = MarkdownTableExtractor.extract(lines);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).headers()).containsExactly("测量参数", "准确度");
        assertThat(blocks.get(0).rows()).hasSize(3);
    }

    @Test
    @DisplayName("表格识别：公式被版面算法误拼成的假表格必须挡掉")
    void rejectsFormulaLookalikeTable() {
        // 实测：国标 PDF 里的公式会被识别成"表格"，格式完全合法（有表头、有分隔行、列数一致），
        // 只有内容看着是公式。照单全收的话，库里的"表格"会全是公式。
        List<String> lines = List.of(
                "| Ein回转 =∑ i=1∫ n | t2 Uin回转 Iin回转dt t1 | …………………………(1) |",
                "|---|---|---|",
                "| Eout回转 =∑ i=1∫ n | t2 Uout回转 Iout回转dt t1 | …………………………(2) …………………………(3) |");

        assertThat(MarkdownTableExtractor.extract(lines))
                .as("公式拼出来的假表格不该被当成表格")
                .isEmpty();
    }

    @Test
    @DisplayName("表格识别：只有一行数据的表按当前策略不收（挡住假表格的代价）")
    void rejectsSingleDataRowTable() {
        List<String> lines = List.of(
                "| 设备名称 | 规格 |",
                "|---|---|",
                "| 挖掘机 | 20 吨 |");

        // 这是刻意的取舍：假表格几乎都只有 1 行数据，去掉它能把误判挡掉一大半，
        // 代价是漏掉"真的只有一行"的表。有文档记录这个边界。
        assertThat(MarkdownTableExtractor.extract(lines)).isEmpty();
    }

    @Test
    @DisplayName("表格识别：列数与表头对不上的残块不收")
    void rejectsInconsistentColumns() {
        List<String> lines = List.of(
                "| A | B | C |",
                "|---|---|---|",
                "| 1 | 2 | 3 |",
                "| 4 | 5 |",
                "| 6 | 7 | 8 |");

        assertThat(MarkdownTableExtractor.extract(lines)).isEmpty();
    }

    @Test
    @DisplayName("表格识别：从正文里剔除表格时，表格行被去掉、其它文字保留")
    void removesTableBlocksFromText() {
        List<String> lines = List.of(
                "前面的说明文字",
                "| 参数 | 值 |",
                "|---|---|",
                "| 电压 | 220V |",
                "| 电流 | 5A |",
                "后面的文字");

        var blocks = MarkdownTableExtractor.extract(lines);
        String body = MarkdownTableExtractor.removeTableBlocks(lines, blocks);

        assertThat(blocks).hasSize(1);
        assertThat(body).contains("前面的说明文字").contains("后面的文字");
        assertThat(body).doesNotContain("电压").doesNotContain("|---|---|");
    }

    @Test
    @DisplayName("表格摘要：包含表头与前几行，超出的部分只报行数")
    void tableSummaryKeepsHeaderAndSamples() {
        List<List<String>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(List.of("行" + i, "值" + i));
        }
        ParsedTable table = new ParsedTable(0, 1, 2, "试验参数表",
                List.of("项目", "取值"), rows, "");

        String summary = table.summary();

        assertThat(summary).contains("试验参数表").contains("项目 | 取值");
        assertThat(summary).contains("行1 | 值1");
        assertThat(summary).as("超出样例数的部分只报个数，不把整张表塞进向量").contains("共 20 行");
        assertThat(summary).doesNotContain("行20");
    }

    // ---------------- 跨页表格合并 ----------------

    @Test
    @DisplayName("跨页合并：带页眉页脚的续表要能接成一张")
    void mergesTableAcrossPagesWithHeaderFooter() {
        // 这是真实国标 PDF 的形态：每页顶部有页眉、底部有页码，表格夹在中间。
        // 最初判据要求"表格必须紧贴页面首/尾"，于是这种续表一次都没合并成。
        String page1 = """
                GB/T 45095—2024
                表4 检验项目
                | 序号 | 检验项目 | 单位 | 限值 | 备注 |
                |---|---|---|---|---|
                | 1 | 绝缘电阻 | MΩ | ≥1 | |
                | 2 | 耐压 | V | 1500 | |

                6""";
        String page2 = """
                GB/T 45095—2024
                | 序号 | 检验项目 | 单位 | 限值 | 备注 |
                |---|---|---|---|---|
                | 3 | 温升 | K | ≤80 | |
                | 4 | 噪声 | dB | ≤85 | |

                7""";

        List<String> warnings = new java.util.ArrayList<>();
        List<ParsedTable> tables = DocumentParser.extractTablesFromPages(
                List.of(page1, page2), warnings);

        assertThat(tables).as("两页上的同列表格应当合并成一张").hasSize(1);
        ParsedTable merged = tables.get(0);
        assertThat(merged.rowCount()).as("数据行应当接起来（2+2）").isEqualTo(4);
        assertThat(merged.pageFrom()).isEqualTo(1);
        assertThat(merged.pageTo()).isEqualTo(2);
        assertThat(merged.rows().get(2)).containsExactly("3", "温升", "K", "≤80", "");
        assertThat(warnings).anyMatch(w -> w.contains("合并"));
    }

    @Test
    @DisplayName("跨页合并：列数不同则不合并（不是同一张表）")
    void doesNotMergeTablesWithDifferentColumns() {
        String page1 = """
                表A
                | 甲 | 乙 |
                |---|---|
                | 1 | 2 |
                | 3 | 4 |

                1""";
        String page2 = """
                表B
                | 甲 | 乙 | 丙 |
                |---|---|---|
                | 5 | 6 | 7 |
                | 8 | 9 | 10 |

                2""";

        List<ParsedTable> tables = DocumentParser.extractTablesFromPages(
                List.of(page1, page2), new java.util.ArrayList<>());

        assertThat(tables).hasSize(2);
    }

    @Test
    @DisplayName("跨页合并：上一页表格后面还有大段正文，就不该往下接")
    void doesNotMergeWhenTableIsNotAtPageEnd() {
        String page1 = """
                表A
                | 甲 | 乙 |
                |---|---|
                | 1 | 2 |
                | 3 | 4 |

                这里还有好几行正文说明，说明上一页的表已经结束了，
                后面这些文字跟表格没有关系。
                继续写下去，让非空行超过容忍度。

                1""";
        String page2 = """
                | 甲 | 乙 |
                |---|---|
                | 5 | 6 |
                | 7 | 8 |

                2""";

        List<ParsedTable> tables = DocumentParser.extractTablesFromPages(
                List.of(page1, page2), new java.util.ArrayList<>());

        assertThat(tables).as("上一页表格后面还有正文，说明表已结束，不该合并").hasSize(2);
    }

    // ---------------- 文档标题提取 ----------------

    @Test
    @DisplayName("标题提取：markdown 的一级标题优先")
    void extractTitleFromHeading() {
        String md = "# 机械家设备年检规定\n\n## 年检周期\n内容";

        assertThat(DocumentParser.extractTitle(md, "文件名")).isEqualTo("机械家设备年检规定");
    }

    @Test
    @DisplayName("标题提取：扫描件 OCR 文本没有 # 标记，也要能猜出真标题")
    void extractTitleFromOcrText() {
        // 这段是真实国标 PDF 经多模态 OCR 后的开头（原本没有 markdown 标记）。
        // 真标题在最后一行；前面全是标准号、页眉、说明性套话。
        String ocr = """
                ICS 73.100.30
                CCS D 92

                中华人民共和国国家标准

                GB/T 25523—2022
                部分代替 GB 25523—2010

                ---

                矿用机械正铲式挖掘机 安全要求

                Electric mining rope shovel—Safety requirements
                """;

        // 拿不到真标题的代价很大：文件名 GBT+25523-2022 和"挖掘机"没有词面交集，
        // 表格摘要带上它之后照样召回不到"挖掘机…危险因素"这个问题。
        assertThat(DocumentParser.extractTitle(ocr, "GBT+25523-2022"))
                .as("应当认出「矿用机械正铲式挖掘机 安全要求」，而不是退回文件名")
                .isEqualTo("矿用机械正铲式挖掘机 安全要求");
    }

    @Test
    @DisplayName("标题提取：实在认不出来时退回文件名，不能乱猜")
    void extractTitleFallsBack() {
        String noise = """
                ICS 73.100.30
                CCS D 92
                GB/T 12345—2020
                部分代替 GB 12345—2010
                """;

        assertThat(DocumentParser.extractTitle(noise, "fallback.pdf")).isEqualTo("fallback.pdf");
        assertThat(DocumentParser.extractTitle("", "empty.pdf")).isEqualTo("empty.pdf");
        assertThat(DocumentParser.extractTitle(null, "null.pdf")).isEqualTo("null.pdf");
    }

    // ---------------- 章节路径识别 ----------------

    @Test
    @DisplayName("章节识别：markdown 标题按 # 的个数定层级")
    void tracksMarkdownHeadings() {
        SectionTracker t = new SectionTracker();

        assertThat(t.accept("# 退货政策")).isEqualTo("退货政策");
        assertThat(t.accept("正文…")).isEqualTo("退货政策");
        assertThat(t.accept("## 无理由退货")).isEqualTo("退货政策 > 无理由退货");
        assertThat(t.accept("### 例外情况")).isEqualTo("退货政策 > 无理由退货 > 例外情况");
        // 同级标题替换而非叠加
        assertThat(t.accept("## 运费承担")).isEqualTo("退货政策 > 运费承担");
    }

    @Test
    @DisplayName("章节识别：条款编号按点分段数定层级（扫描件 OCR 没有 # 标记，编号是唯一锚点）")
    void tracksClauseNumbers() {
        SectionTracker t = new SectionTracker();

        assertThat(t.accept("5 安全要求")).isEqualTo("5 安全要求");
        assertThat(t.accept("5.4 润滑系统")).isEqualTo("5 安全要求 > 5.4 润滑系统");
        assertThat(t.accept("5.5 电气系统")).isEqualTo("5 安全要求 > 5.5 电气系统");
        assertThat(t.accept("6 试验方法")).isEqualTo("6 试验方法");
    }

    @Test
    @DisplayName("章节识别：更深的编号只是条内序号，不该把每一款都变成一节")
    void ignoresDeepClauseNumbers() {
        SectionTracker t = new SectionTracker();
        t.accept("5 安全要求");
        t.accept("5.4 润滑系统");

        // 再往下的 5.4.4.1 是"款"，如果它也算章节，每个句子都会自成一段，
        // 回填时反而取不到上下文
        assertThat(t.accept("5.4.4.1 润滑系统应安全可靠")).isEqualTo("5 安全要求 > 5.4 润滑系统");
        assertThat(t.accept("5.4.4.2 减速机应…")).isEqualTo("5 安全要求 > 5.4 润滑系统");
    }

    @Test
    @DisplayName("章节识别：年份不能被当成章节号")
    void doesNotTreatYearAsClause() {
        SectionTracker t = new SectionTracker();
        t.accept("5 安全要求");

        // 国标正文里到处是 "GB/T 25523—2022"，但 "2022 年" 这种行也可能以数字开头
        assertThat(t.accept("2022 年第 3 号公告"))
                .as("年份行不该冲掉当前章节")
                .isEqualTo("5 安全要求");
    }

    @Test
    @DisplayName("目录行识别：点线连页码的才是目录行")
    void detectsTocLines() {
        assertThat(SectionTracker.isTocLine("5.4 润滑系统 ……………………………… 9")).isTrue();
        assertThat(SectionTracker.isTocLine("1 范围 ......... 1")).isTrue();
        // 正文行不是目录行
        assertThat(SectionTracker.isTocLine("5.4 润滑系统")).isFalse();
        assertThat(SectionTracker.isTocLine("润滑系统应安全可靠，见 GB/T 25523")).isFalse();
    }

    // ---------------- 结构切分（页码 × 章节） ----------------

    @Test
    @DisplayName("结构切分：页码变化就断段，同一页内同章节连成一段")
    void blocksBreakAtPageBoundary() {
        List<String> pages = List.of(
                "5 安全要求\n5.4 润滑系统\n润滑系统应安全可靠，并设置压力指示装置，"
                        + "当润滑系统发生故障应发出报警信号。\n5.4.4.2 减速机应便于维护。",
                "5.4 润滑系统\n减速机应设置油位观察窗，便于日常检查油位是否正常，"
                        + "油位过低时应及时补充。");

        List<DocBlock> blocks = DocumentParser.buildBlocks(pages, "文档标题", new java.util.ArrayList<>());

        assertThat(blocks).as("两页各自成段（页码是断段依据）").hasSize(2);
        assertThat(blocks.get(0).pageNo()).isEqualTo(1);
        assertThat(blocks.get(0).sectionPath()).isEqualTo("5 安全要求 > 5.4 润滑系统");
        assertThat(blocks.get(1).pageNo()).isEqualTo(2);
        // 第 2 页接着同一节，路径不变
        assertThat(blocks.get(1).sectionPath()).isEqualTo("5 安全要求 > 5.4 润滑系统");
        assertThat(blocks.get(1).text()).contains("油位观察窗");
    }

    @Test
    @DisplayName("结构切分：章节标题行留下的碎段并入下一段，不产生孤立小块")
    void mergesTinyHeadingBlocksForward() {
        // 只有一行章节标题时，它自己会成为一个 10 个字的段。
        // 这种段切出来就是十字切片，检索命中它没有任何信息量，纯属污染索引。
        List<String> pages = List.of("5 安全要求\n本文件规定了矿用机械正铲式挖掘机的安全要求，"
                + "给出了其在全生命周期内可能产生的危险。");

        List<DocBlock> blocks = DocumentParser.buildBlocks(pages, "文档标题", new java.util.ArrayList<>());

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text())
                .as("标题应当和它领起的内容在同一段里")
                .contains("5 安全要求")
                .contains("全生命周期");
    }

    @Test
    @DisplayName("结构切分：目录页整页剔除，并留下说明")
    void excludesTocPages() {
        List<String> pages = List.of(
                "目 次\n1 范围 ……………………………… 1\n2 规范性引用文件 …………………… 2\n"
                        + "3 术语和定义 ……………………… 3\n5.4 润滑系统 ……………………… 9",
                "1 范围\n本文件规定了矿用机械正铲式挖掘机的安全要求。");

        List<String> warnings = new java.util.ArrayList<>();
        List<DocBlock> blocks = DocumentParser.buildBlocks(pages, "文档标题", warnings);

        assertThat(blocks).as("目录页不该产生任何块").hasSize(1);
        assertThat(blocks.get(0).pageNo()).isEqualTo(2);
        assertThat(blocks.get(0).text()).contains("矿用机械正铲式挖掘机");
        assertThat(warnings).anyMatch(w -> w.contains("目录"));
    }

    @Test
    @DisplayName("结构切分：识别不出章节时用文档标题兜底，且页码仍然保留")
    void fallsBackToDocumentTitle() {
        List<String> pages = List.of("一段没有任何章节标记的正文。");

        List<DocBlock> blocks = DocumentParser.buildBlocks(
                pages, "某份说明书", new java.util.ArrayList<>());

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).sectionPath()).isEqualTo("某份说明书");
        assertThat(blocks.get(0).pageLabel()).isEqualTo("第 1 页");
    }

    // ---------------- 输入校验 ----------------

    @Test
    @DisplayName("不支持的文件类型要有可读的报错，而不是抛原始异常")
    void rejectsUnsupportedExtension() {
        // 这个用例不需要 Spring 容器：解析器在检查扩展名时就返回了，
        // 两个外部客户端传 null 也不会被用到
        DocumentParser parser = new DocumentParser(null, null);

        assertThatThrownBy(() -> parser.parse("资料.exe", new byte[]{1, 2, 3}))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("暂不支持的文件类型");
    }

    @Test
    @DisplayName("空文件被拒绝")
    void rejectsEmptyFile() {
        DocumentParser parser = new DocumentParser(null, null);

        assertThatThrownBy(() -> parser.parse("a.txt", new byte[0]))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("内容为空");
    }

    @Test
    @DisplayName("扩展名解析：大小写、无扩展名、路径分隔符")
    void parsesExtension() {
        assertThat(DocumentParser.extensionOf("A.PDF")).isEqualTo("pdf");
        assertThat(DocumentParser.extensionOf("无扩展名")).isEmpty();
        assertThat(DocumentParser.extensionOf("a.")).isEmpty();
        assertThat(DocumentParser.extensionOf("x.tar.gz")).isEqualTo("gz");
    }

    // ---------------- 真解析（可选） ----------------

    /**
     * 拿仓库里 file/ 下的国标 PDF 真跑一遍。
     *
     * <p>默认不跑：要起 LiteParse 进程、要几十秒，而且换台机器就没有这些样本了。
     * 需要时：
     * <pre>
     *   mvn test -Dtest=DocumentUploadTest -Dparse.eval=true
     * </pre>
     */
    @SpringBootTest
    @EnabledIfSystemProperty(named = "parse.eval", matches = "true")
    static class RealDocumentTest {

        @Autowired
        private DocumentParser parser;

        @Test
        @DisplayName("真解析：国标 PDF 能抽出表格，并给出页数/OCR 页数")
        void parsesRealStandardPdf() throws Exception {
            Path pdf = Path.of("file/GBT+45095-2024.pdf");
            org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(pdf), "样本不存在，跳过");

            ParsedDocument doc = parser.parse(pdf.getFileName().toString(), Files.readAllBytes(pdf));

            System.out.printf("%n[真解析] 页数=%d  OCR页数=%d  表格数=%d  正文长度=%d%n",
                    doc.pageCount(), doc.ocrPages(), doc.tables().size(), doc.text().length());
            for (ParsedTable t : doc.tables()) {
                System.out.printf("  表%d: 第%d-%d页 %d行×%d列  标题=%s%n",
                        t.tableIndex() + 1, t.pageFrom(), t.pageTo(), t.rowCount(), t.colCount(),
                        t.caption() == null ? "(无)" : t.caption());
            }
            doc.warnings().forEach(w -> System.out.println("  警告: " + w));

            assertThat(doc.pageCount()).isPositive();
            assertThat(doc.tables()).as("国标文档里应当能认出表格").isNotEmpty();
        }
    }
}
