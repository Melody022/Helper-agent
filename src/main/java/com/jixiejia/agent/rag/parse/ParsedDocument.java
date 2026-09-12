package com.jixiejia.agent.rag.parse;

import java.util.List;

/**
 * 一份文档解析完的结果。
 *
 * <p>{@link #text} 是<b>去掉表格之后</b>的正文——表格不会混在正文里被切片，
 * 它们以 {@link #tables} 的形式单独存、单独索引。这是"方案 A"的关键分工：
 * 正文走常规切片，表格走"摘要向量化 + 本体取回"。
 *
 * @param text     正文（已剔除表格块）
 * @param title    标题
 * @param pageCount 总页数
 * @param ocrPages 走了多模态 OCR 的页数（按量计费，要如实告诉用户）
 * @param tables   抽出来的表格
 * @param warnings 降级/异常情况说明，供前端展示
 */
public record ParsedDocument(String text, String title, int pageCount, int ocrPages,
                             List<ParsedTable> tables, List<String> warnings) {
}
