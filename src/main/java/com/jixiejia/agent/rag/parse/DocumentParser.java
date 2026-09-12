package com.jixiejia.agent.rag.parse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 文档解析的总入口：把上传的文件变成「正文 + 表格列表」。
 *
 * <p>整体流程：
 * <pre>
 *   文件 ──┬─ .md/.txt ──────────────→ 直接读文本
 *          ├─ 图片 ───────────────────→ 多模态 OCR
 *          └─ .pdf/.docx/... ──→ lit：逐页探测 → 分流 → 抽表 → 跨页合并
 * </pre>
 *
 * <h2>分流为什么要看 textCoverage 而不是 needsOcr</h2>
 *
 * <p>见 {@link LiteParseClient.PageInfo#needsRealOcr}。简单说：{@code needsOcr} 会被
 * "页面里内嵌了图片"点亮，照它分流会把大量文本完好的页送去多模态白烧钱。
 *
 * <h2>跨页表格怎么合并</h2>
 *
 * <p>没有任何一个 PDF 解析库会告诉你"这张表跨页了"——PDF 里根本没有"表格对象"，
 * 只有一堆带坐标的文本片段。这里用的是一个<b>版面启发式</b>：
 * 如果第 N 页的 markdown <b>以表格结尾</b>，而第 N+1 页<b>以同列数的表格开头</b>，
 * 就认为它们是同一张被切断的表，把数据行接起来、去掉重复的表头。
 *
 * <p>这个判据会有误判（比如上一页末尾是表、下一页开头恰好是另一张同列数的表）。
 * 之所以敢用：<b>误判的代价是把两张表拼在一起，而漏判的代价是检索到半张表给出错误数据</b>。
 * 在国标这类文档里，后者发生得更频繁也更危险。真要更准，得拿坐标做列对齐，
 * 那需要按页调 {@code lit parse --format json} 拿到 textItems——成本高得多。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParser {

    /** 允许的扩展名。不是白名单里的一律拒绝。 */
    private static final Map<String, Kind> EXTENSIONS = Map.ofEntries(
            Map.entry("pdf", Kind.LITEPARSE),
            Map.entry("docx", Kind.LITEPARSE),
            Map.entry("doc", Kind.LITEPARSE),
            Map.entry("xlsx", Kind.LITEPARSE),
            Map.entry("xls", Kind.LITEPARSE),
            Map.entry("pptx", Kind.LITEPARSE),
            Map.entry("odt", Kind.LITEPARSE),
            Map.entry("md", Kind.PLAIN_TEXT),
            Map.entry("markdown", Kind.PLAIN_TEXT),
            Map.entry("txt", Kind.PLAIN_TEXT),
            Map.entry("csv", Kind.PLAIN_TEXT),
            Map.entry("png", Kind.IMAGE),
            Map.entry("jpg", Kind.IMAGE),
            Map.entry("jpeg", Kind.IMAGE),
            Map.entry("bmp", Kind.IMAGE),
            Map.entry("webp", Kind.IMAGE),
            Map.entry("tif", Kind.IMAGE),
            Map.entry("tiff", Kind.IMAGE));

    private enum Kind { LITEPARSE, PLAIN_TEXT, IMAGE }

    /**
     * 哪些格式才需要判断"这页有没有文本层"。
     *
     * <p><b>只有 PDF</b>：它既可能是原生电子版（有文本层），也可能是扫描件（没有）。
     * Office 文档是从文字格式生成的，一定有文本层；图片走 {@link Kind#IMAGE} 分支直接 OCR。
     * 对这两类做"要不要 OCR"的探测没有意义，还会踩到 LiteParse 对 Office 文档
     * 处理不一致的问题（见 {@link #parseWithLiteParse} 里的注释）。
     */
    private static final java.util.Set<String> MAY_NEED_OCR = java.util.Set.of("pdf");

    private final LiteParseClient liteParse;
    private final MultimodalOcrClient ocr;

    /**
     * 判定"这页没有文本层"的覆盖率阈值。
     *
     * <p>实测参考：一份文本完好的简历 PDF 是 0.25；国标扫描件是 0.008~0.08。
     * 取 0.02 是为了只捞真正的扫描页，不误伤"文字少但正常"的页（比如整页一张大图的封面）。
     */
    @Value("${rag.parse.ocr.coverage-threshold:0.02}")
    private double coverageThreshold;

    /** 截图 DPI。太高会让图片 token 暴涨，200 是清晰度与成本的平衡点。 */
    @Value("${rag.parse.ocr.dpi:200}")
    private int ocrDpi;

    /** 单次上传最多 OCR 多少页。多模态按量计费，必须有个上限兜住最坏情况。 */
    @Value("${rag.parse.ocr.max-pages:50}")
    private int maxOcrPages;

    /**
     * OCR 并发度。各页互相独立，串行跑纯属浪费等待时间。
     *
     * <p>默认 4：一份 21 页的扫描件实测串行要近 6 分钟，并发 4 能压到 1~2 分钟。
     * 再往上收益递减，而且有被 DashScope 限流的风险。
     */
    @Value("${rag.parse.ocr.concurrency:4}")
    private int ocrConcurrency;

    /**
     * 解析一份文档。
     *
     * @param filename 原始文件名，只用来取扩展名和当标题——<b>绝不拿它拼磁盘路径</b>
     * @param bytes    文件内容
     */
    public ParsedDocument parse(String filename, byte[] bytes) {
        String ext = extensionOf(filename);
        Kind kind = EXTENSIONS.get(ext);
        if (kind == null) {
            throw new DocumentParseException("暂不支持的文件类型：." + ext
                    + "（支持 PDF、Word、Excel、PPT、图片、md/txt/csv）");
        }
        if (bytes == null || bytes.length == 0) {
            throw new DocumentParseException("文件内容为空");
        }

        String title = stripExtension(filename);
        ParsedDocument parsed = switch (kind) {
            case PLAIN_TEXT -> parsePlainText(title, bytes);
            case IMAGE -> parseImage(title, bytes);
            case LITEPARSE -> parseWithLiteParse(title, ext, bytes);
        };

        // 标题优先取正文里的一级标题，取不到才退回文件名。
        //
        // 为什么重要：上传的国标文件名是 "GBT+25523-2022.pdf"，光看这个标题，
        // 向量库里那条记录和"挖掘机"没有任何词面交集；而正文 H1 是
        // "矿用机械正铲式挖掘机 安全要求"——**含"挖掘机"，是能被召回的关键**。
        // 实测用户问"挖掘机生命周期可能出现什么危险因素"，表格摘要因为标题里
        // 没有"挖掘机"而落选，明明表里就是答案。
        String better = extractTitle(parsed.text(), title);
        return better.equals(parsed.title()) ? parsed
                : new ParsedDocument(parsed.text(), better, parsed.pageCount(),
                        parsed.ocrPages(), parsed.tables(), parsed.warnings());
    }

    /**
     * 取文档标题：优先正文里的一级标题，取不到就从 OCR 文本里猜一个像标题的行。
     *
     * <p><b>为什么非要拿到真标题。</b>上传的国标文件名是 {@code GBT+25523-2022.pdf}，
     * 拿它当标题的话，这份文档在向量库里和"挖掘机"**没有任何词面交集**——
     * 而它的正文标题是《矿用机械正铲式挖掘机 安全要求》。
     * 实测用户问"挖掘机生命周期可能出现什么危险因素"，答案就是本文档的
     * "表 1 危险一览表"，但表格摘要前缀是文件名，向量召回和 BM25 双双输给了
     * 满篇"挖掘机"的其它段落，模型拿到资料后只能说"表1的内容没有提供"。
     *
     * <p>OCR 出来的文本没有 markdown 的 {@code #} 标记（扫描件里的标题就是一行普通文字），
     * 所以还得有个启发式兜底：**在开头若干行里找一行"像标题的中文短行"**，
     * 排除标准号、页眉、说明性套话这些噪声。
     */
    public static String extractTitle(String markdown, String fallback) {
        if (markdown == null || markdown.isBlank()) {
            return fallback;
        }
        String[] lines = markdown.split("\n", 60);

        // ① markdown 一级标题最可靠
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("# ")) {
                String heading = t.substring(2).trim();
                if (!heading.isBlank()) {
                    return clamp(heading);
                }
            }
        }

        // ② OCR 文本：找一行像标题的中文短行
        for (String line : lines) {
            String t = line.trim();
            if (isLikelyTitle(t)) {
                return clamp(t);
            }
        }
        return fallback;
    }

    /** 标题的长度上限，超了说明不是标题（数据库列是 varchar(500)，这里留足余量）。 */
    private static String clamp(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /**
     * 这一行像不像"文档标题"。
     *
     * <p>判据是照着真实国标 PDF 的开头调的——那里依次出现
     * {@code ICS 73.100.30}、{@code 中华人民共和国国家标准}、{@code GB/T 25523—2022}、
     * {@code 部分代替 GB 25523—2010}，最后才是真正的标题
     * {@code 矿用机械正铲式挖掘机 安全要求}。前几行都是噪声，必须排除掉。
     */
    private static boolean isLikelyTitle(String line) {
        if (line.isBlank() || line.length() < 4 || line.length() > 60) {
            return false;
        }
        // 必须含中文：纯英文/纯数字/标准号行不是我们要的标题
        long cjk = line.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        if (cjk < 4) {
            return false;
        }
        // 中文占比要够高，排除"部分代替 GB 25523—2010"这类中英混排的说明行
        if ((double) cjk / line.length() < 0.6) {
            return false;
        }
        // 排掉常见套话与页眉页脚
        String[] noise = {"国家标准", "部分代替", "代替", "发布", "实施", "前言", "目次",
                "范围", "规范性引用", "术语和定义", "版权", "ICS", "CCS"};
        for (String n : noise) {
            if (line.contains(n)) {
                return false;
            }
        }
        // 以标点结尾的多半是正文句子，不是标题
        char last = line.charAt(line.length() - 1);
        return "。；，、！？.,;".indexOf(last) < 0;
    }

    // ---------------- 纯文本 / 图片 ----------------

    private ParsedDocument parsePlainText(String title, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // 纯文本也过一遍表格抽取：md 里本来就可能带 markdown 表格
        return assemble(title, text, 1, 0, List.of());
    }

    private ParsedDocument parseImage(String title, byte[] bytes) {
        if (!ocr.configured()) {
            throw new DocumentParseException("图片解析需要配置多模态 OCR（DASHSCOPE_API_KEY）");
        }
        String text = ocr.ocr(bytes);
        if (text == null || text.isBlank()) {
            throw new DocumentParseException("图片识别失败，没有提取到文字");
        }
        return assemble(title, text, 1, 1, List.of());
    }

    // ---------------- LiteParse 路径 ----------------

    private ParsedDocument parseWithLiteParse(String title, String ext, byte[] bytes) {
        if (!liteParse.available()) {
            throw new DocumentParseException(
                    "文档解析工具 LiteParse 不可用，请先安装（npm i -g @llamaindex/liteparse）");
        }

        // 临时目录 + 自己定的文件名：用户提供的文件名不参与路径拼接，避免路径穿越
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("jxj-parse-");
            Path file = workDir.resolve("upload." + ext);
            Files.write(file, bytes);

            List<String> warnings = new ArrayList<>();

            // ① 逐页探测，决定哪些页需要 OCR
            //
            // ⚠️ 只对 PDF 做这一步。Office 文档（docx/xlsx/pptx）本来就是从文字格式生成的，
            // **永远有文本层、永远不需要 OCR**；而实测 `lit is-complex` 对 Office 文档会报
            // "LibreOffice is not installed"（同一个文件 `lit parse` 却正常）——
            // 照原样走下去会把正常 docx 判成"有扫描页"，再触发一次注定失败的截图，
            // 用户看到一条莫名其妙的"扫描页 OCR 失败"警告。
            //
            // 探测本身也做成非致命：探测失败就退化成"不做 OCR"，而不是整篇解析失败。
            List<Integer> ocrPages = List.of();
            int pageCount = 1;
            if (MAY_NEED_OCR.contains(ext)) {
                try {
                    List<LiteParseClient.PageInfo> pages = liteParse.inspect(file);
                    pageCount = pages.size();
                    ocrPages = pages.stream()
                            .filter(p -> p.needsRealOcr(coverageThreshold))
                            .map(LiteParseClient.PageInfo::pageNumber)
                            .toList();
                } catch (Exception e) {
                    warnings.add("文档结构探测失败，本次不做 OCR 处理：" + e.getMessage());
                }
            }

            if (ocrPages.size() > maxOcrPages) {
                throw new DocumentParseException(String.format(
                        "这份文档有 %d 页需要 OCR，超过单次上限 %d 页。"
                                + "请拆分后再上传（扫描件 OCR 按量计费，设上限是为了避免意外开销）。",
                        ocrPages.size(), maxOcrPages));
            }

            // ② 整篇抽 markdown，按分页符切成每页
            String markdown = liteParse.parseMarkdown(file, null);
            List<String> pageTexts = splitPages(markdown, pageCount, warnings);

            // ③ 需要 OCR 的页，用多模态重写该页内容
            int ocrDone = 0;
            if (!ocrPages.isEmpty()) {
                if (!ocr.configured()) {
                    warnings.add("有 " + ocrPages.size() + " 页是扫描件，但未配置多模态 OCR，这些页将是空的");
                } else {
                    ocrDone = applyOcr(file, pageTexts, ocrPages, warnings);
                }
            }

            // ④ 逐页抽表 → 跨页合并 → 从正文里剔掉表格
            List<ParsedTable> tables = extractTablesFromPages(pageTexts, warnings);
            StringBuilder body = new StringBuilder();
            for (String pageText : pageTexts) {
                List<MarkdownTableExtractor.Block> blocks =
                        MarkdownTableExtractor.extract(List.of(pageText.split("\n", -1)));
                body.append(MarkdownTableExtractor.removeTableBlocks(
                        List.of(pageText.split("\n", -1)), blocks)).append('\n');
            }

            return new ParsedDocument(body.toString().trim(), title, pageTexts.size(), ocrDone,
                    tables, warnings);

        } catch (IOException e) {
            throw new DocumentParseException("处理上传文件失败：" + e.getMessage(), e);
        } finally {
            deleteQuietly(workDir);
        }
    }

    // ---------------- 解析辅助 ----------------

    /**
     * 按分页符把整篇 markdown 切成每页。
     *
     * <p>分页符是恰好 5 个短横线（{@link LiteParseClient#pageSeparator()}）。
     * <b>必须用"分隔符个数 == 页数-1"交叉校验</b>：文档正文里本来就可能出现短横线
     * （表格分隔、水平线），万一真出现一个孤立的 {@code -----}，切错了会把正文搅乱。
     * 对不上就整篇当成一页，宁可不分页也不要错位。
     */
    private List<String> splitPages(String markdown, int expectedPages, List<String> warnings) {
        List<String> lines = List.of(markdown.split("\n", -1));
        List<Integer> separators = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (LiteParseClient.pageSeparator().equals(lines.get(i).trim())) {
                separators.add(i);
            }
        }

        if (expectedPages > 1 && separators.size() != expectedPages - 1) {
            warnings.add(String.format(
                    "分页符数量（%d）与页数（%d）对不上，本次不做分页处理（跨页表格可能无法合并）",
                    separators.size(), expectedPages));
            return List.of(markdown);
        }
        if (separators.isEmpty()) {
            return List.of(markdown);
        }

        List<String> pages = new ArrayList<>();
        int from = 0;
        for (int sep : separators) {
            pages.add(String.join("\n", lines.subList(from, sep)));
            from = sep + 1;
        }
        pages.add(String.join("\n", lines.subList(from, lines.size())));
        return pages;
    }

    /**
     * 把扫描页替换成多模态 OCR 的结果。返回成功 OCR 的页数。
     *
     * <p><b>为什么并发做。</b>最初是一页一页串行调的，实测一份 21 页的扫描件要跑近 6 分钟——
     * 用户那边看到的是"上传一直转圈"，第一反应就是"是不是卡死了"。而各页之间**完全独立**
     * （各自的图片、各自的请求、顺序只影响结果摆放），天然可以并发。
     * 并发度默认 4：既能压掉大部分等待，又不会把 DashScope 打出限流。
     */
    private int applyOcr(Path file, List<String> pageTexts, List<Integer> ocrPages, List<String> warnings) {
        Path shotDir = null;
        ExecutorService pool = null;
        try {
            shotDir = Files.createTempDirectory("jxj-shot-");
            List<Path> images = liteParse.screenshot(file, ocrPages, ocrDpi, shotDir);
            if (images.size() != ocrPages.size()) {
                warnings.add(String.format("预期截图 %d 张，实际 %d 张，按顺序对应",
                        ocrPages.size(), images.size()));
            }

            int count = Math.min(images.size(), ocrPages.size());
            if (count == 0) {
                return 0;
            }

            pool = Executors.newFixedThreadPool(Math.max(1, Math.min(ocrConcurrency, count)), r -> {
                Thread t = new Thread(r, "jxj-ocr");
                t.setDaemon(true);
                return t;
            });

            List<Future<String>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Path image = images.get(i);
                int pageNo = ocrPages.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        String text = ocr.ocr(Files.readAllBytes(image));
                        // 逐页记日志。不记的话这个流程完全不可观测——
                        // 实测用户传一份 15 页扫描件等了十几分钟，日志里只有一条
                        // "lit 拿到了输出"，根本看不出是在推进还是卡死了。
                        log.info("OCR 完成：第 {}/{} 页（页码 {}），{} 字",
                                pageNo, ocrPages.size(), pageNo,
                                text == null ? 0 : text.length());
                        return text;
                    } catch (Exception e) {
                        // 单页失败不该拖垮整篇：记下来，这一页就是空的
                        log.warn("第 {} 页 OCR 失败：{}", pageNo, e.toString());
                        return null;
                    }
                }));
            }

            log.info("开始 OCR：共 {} 页，并发度 {}", count, Math.max(1, Math.min(ocrConcurrency, count)));

            int done = 0;
            for (int i = 0; i < count; i++) {
                String text = futures.get(i).get();
                int pageNo = ocrPages.get(i);
                if (text == null || text.isBlank()) {
                    warnings.add("第 " + pageNo + " 页 OCR 没有识别出内容");
                    continue;
                }
                pageTexts.set(pageNo - 1, text);
                done++;
            }
            return done;

        } catch (Exception e) {
            warnings.add("扫描页 OCR 失败，这些页将没有文字：" + e.getMessage());
            return 0;
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
            deleteQuietly(shotDir);
        }
    }

    /**
     * 逐页抽表，并把跨页切断的表接回去。
     *
     * <p>抽出来是为了能单测：这段逻辑的输入就是"每页的 markdown"，不依赖进程，
     * 而它恰恰是最容易写错、也最难在真实文档上验证的部分。
     *
     * @param pageTexts 每页的 markdown
     * @param warnings  合并情况会追加到这里
     */
    public static List<ParsedTable> extractTablesFromPages(List<String> pageTexts, List<String> warnings) {
        List<ParsedTable> tables = new ArrayList<>();
        // 上一页是不是"以表格结尾"。**要用上一页自己的 lines 判断完再带过来**——
        // 最初写成在处理本页时用本页的 lines 去量上一页的 blocks，行号对不上，
        // 导致真实的跨页续表一次都没合并成。
        boolean prevPageEndsWithTable = false;

        for (int i = 0; i < pageTexts.size(); i++) {
            List<String> lines = List.of(pageTexts.get(i).split("\n", -1));
            List<MarkdownTableExtractor.Block> blocks =
                    new ArrayList<>(MarkdownTableExtractor.extract(lines));

            // 跨页合并：上一页以表格结尾 + 本页以同列数表格开头 → 接成一张
            if (prevPageEndsWithTable && !blocks.isEmpty() && !tables.isEmpty()
                    && isWithinLeadingEdge(blocks.get(0), lines)) {
                ParsedTable prev = tables.get(tables.size() - 1);
                MarkdownTableExtractor.Block head = blocks.get(0);
                if (prev.colCount() == head.headers().size()) {
                    ParsedTable merged = mergeAcrossPages(tables.remove(tables.size() - 1), head, i + 1);
                    tables.add(merged);
                    blocks.remove(0);
                    warnings.add(String.format("第 %d-%d 页的表格已合并为一张（共 %d 行 %d 列）",
                            merged.pageFrom(), merged.pageTo(), merged.rowCount(), merged.colCount()));
                }
            }

            for (MarkdownTableExtractor.Block b : blocks) {
                tables.add(new ParsedTable(tables.size(), i + 1, i + 1, captionFor(lines, b),
                        b.headers(), b.rows(), toMarkdown(b)));
            }

            prevPageEndsWithTable = !blocks.isEmpty() && isWithinTrailingEdge(lines, blocks);
        }
        return tables;
    }

    /**
     * 页眉/页脚能容忍几行。
     *
     * <p><b>这个常数是"跨页合并能不能生效"的关键。</b>国标、论文、报告这类文档
     * <b>每页都有页眉</b>（如 "GB/T 45095—2024"）和页脚页码，所以一张横跨两页的表
     * 永远不可能是页面的第一行或最后一行。最初判据写的是"表格必须紧贴页面首/尾"，
     * 结果测试文档里明明有两页都标着"表4 检验项目（续）"的续表，却一次都没合并成。
     */
    private static final int EDGE_TOLERANCE_LINES = 2;

    /** 表格块是否"贴着页面顶部"（允许前面有页眉等少量非空行）。 */
    private static boolean isWithinLeadingEdge(MarkdownTableExtractor.Block block, List<String> lines) {
        int nonBlank = 0;
        for (int i = 0; i < block.startLine(); i++) {
            if (!lines.get(i).isBlank() && ++nonBlank > EDGE_TOLERANCE_LINES) {
                return false;
            }
        }
        return true;
    }

    /** 表格块是否"贴着页面底部"（允许后面有页码等少量非空行）。 */
    private static boolean isWithinTrailingEdge(List<String> lines, List<MarkdownTableExtractor.Block> blocks) {
        int end = blocks.get(blocks.size() - 1).endLineExclusive();
        int nonBlank = 0;
        for (int i = end; i < lines.size(); i++) {
            if (!lines.get(i).isBlank() && ++nonBlank > EDGE_TOLERANCE_LINES) {
                return false;
            }
        }
        return true;
    }

    /** 把上一页的表和下一页的头接起来：数据行追加，重复的表头丢掉。 */
    private static ParsedTable mergeAcrossPages(ParsedTable prev, MarkdownTableExtractor.Block next, int pageTo) {
        List<List<String>> rows = new ArrayList<>(prev.rows());
        rows.addAll(next.rows());
        ParsedTable merged = new ParsedTable(prev.tableIndex(), prev.pageFrom(), pageTo,
                prev.caption(), prev.headers(), rows, "");
        // markdown 要用合并后的完整数据重新生成
        return new ParsedTable(merged.tableIndex(), merged.pageFrom(), merged.pageTo(),
                merged.caption(), merged.headers(), merged.rows(), toMarkdown(merged));
    }

    /** 把一张表还原成 markdown。 */
    static String toMarkdown(ParsedTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", table.headers())).append(" |\n");
        sb.append("|").append("---|".repeat(table.headers().size())).append('\n');
        for (List<String> row : table.rows()) {
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return sb.toString().trim();
    }

    private static String toMarkdown(MarkdownTableExtractor.Block block) {
        return toMarkdown(new ParsedTable(0, 0, 0, null, block.headers(), block.rows(), ""));
    }

    /** 取表格上方最近的一行非空文字当标题。 */
    private static String captionFor(List<String> lines, MarkdownTableExtractor.Block block) {
        for (int i = block.startLine() - 1; i >= 0 && i >= block.startLine() - 5; i--) {
            String t = lines.get(i).trim();
            if (!t.isEmpty() && !t.startsWith("|")) {
                return t.length() > 200 ? t.substring(0, 200) : t;
            }
        }
        return null;
    }

    /** 从文件名取扩展名，小写。没有扩展名时返回空串。 */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1
                ? "" : filename.substring(dot + 1).toLowerCase();
    }

    private static String stripExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "未命名文档";
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isBlank() ? "未命名文档" : base;
    }

    /** 给纯文本/图片路径补上表格抽取，保持三条路径输出一致。 */
    private ParsedDocument assemble(String title, String rawText, int pageCount, int ocrPages,
                                    List<String> extraWarnings) {
        List<String> lines = List.of(rawText.split("\n", -1));
        List<MarkdownTableExtractor.Block> blocks = MarkdownTableExtractor.extract(lines);

        List<ParsedTable> tables = new ArrayList<>();
        for (MarkdownTableExtractor.Block b : blocks) {
            tables.add(new ParsedTable(tables.size(), 1, 1, captionFor(lines, b),
                    b.headers(), b.rows(), toMarkdown(b)));
        }

        List<String> warnings = new ArrayList<>(extraWarnings);
        return new ParsedDocument(MarkdownTableExtractor.removeTableBlocks(lines, blocks).trim(),
                title, pageCount, ocrPages, tables, warnings);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时文件删不掉不值得让整个上传失败
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 供上层做展示：支持的扩展名清单。 */
    public static String supportedExtensions() {
        return String.join("、", new java.util.TreeSet<>(EXTENSIONS.keySet()));
    }
}
