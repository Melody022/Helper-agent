package com.jixiejia.agent.rag.parse;

import java.util.List;

/**
 * 一份文档解析完的结果。
 *
 * <p>{@link #text} 是<b>去掉表格之后</b>的正文——表格不会混在正文里被切片，
 * 它们以 {@link #tables} 的形式单独存、单独索引。这是"方案 A"的关键分工：
 * 正文走常规切片，表格走"摘要向量化 + 本体取回"。
 *
 * <p>{@link #blocks} 是把正文按「页 × 章节」拆开后的结果，带着页码和章节路径。
 * 切片器按段切块，块就继承了这两个元数据——**溯源和上下文回填都靠它**。
 *
 * @param text      正文（已剔除表格），供内容指纹与降级使用
 * @param title     标题
 * @param pageCount 总页数
 * @param ocrPages  走了多模态 OCR 的页数（按量计费，要如实告诉用户）
 * @param tables    抽出来的表格
 * @param warnings  降级/异常情况说明，供前端展示
 * @param blocks    带页码与章节路径的正文段
 */
public record ParsedDocument(String text, String title, int pageCount, int ocrPages,
                             List<ParsedTable> tables, List<String> warnings,
                             List<DocBlock> blocks) {

    /**
     * 没有结构信息时的兜底构造：整篇作为一个块。
     *
     * <p>纯文本、短文档、以及测试用得到——它们本来就没有页码和章节可言。
     */
    public ParsedDocument(String text, String title, int pageCount, int ocrPages,
                          List<ParsedTable> tables, List<String> warnings) {
        this(text, title, pageCount, ocrPages, tables, warnings,
                List.of(new DocBlock(1, Math.max(1, pageCount), title, text == null ? "" : text)));
    }
}
