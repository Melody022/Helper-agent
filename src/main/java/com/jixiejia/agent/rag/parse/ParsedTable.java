package com.jixiejia.agent.rag.parse;

import java.util.List;

/**
 * 从一份文档里抽出来的一张表。
 *
 * <p>这是"方案 A"的核心产物：表格本体<b>以结构化的形式单独存</b>，不跟着正文切片。
 * 切片用的只是 {@link #summary()}——表头加几行样例，够检索命中就行；
 * 命中之后再按 id 把 {@link #markdown()} 的完整表格取回来喂给模型。
 *
 * @param tableIndex 文档内第几张表（从 0 开始）
 * @param pageFrom   起始页（1 起）
 * @param pageTo     结束页；跨页合并过的表会大于 pageFrom
 * @param caption    表格上方的标题行，没有则为 null
 * @param headers    表头单元格，如 ["测量参数", "准确度"]
 * @param rows       数据行，每行是单元格列表
 * @param markdown   完整 markdown 表格，检索命中后原样喂给模型
 */
public record ParsedTable(int tableIndex, int pageFrom, int pageTo, String caption,
                          List<String> headers, List<List<String>> rows, String markdown) {

    /** 数据行数（不含表头）。 */
    public int rowCount() {
        return rows.size();
    }

    public int colCount() {
        return headers.size();
    }

    /**
     * 用于向量化的摘要文本：标题 + 表头 + 前几行。
     *
     * <p><b>为什么向量化的是摘要而不是整张表。</b>一张跨页大表动辄几百行，
     * 直接切片向量化会被切散，检索到的只是残片；而且长表格的向量表征很糊，
     * 拿它做相似度匹配反而不准。摘要（表头 + 少量样例行）足以让"问这张表"的问题命中它。
     *
     * <p>样例行取前若干行是为了让摘要里带上真实的数据形态（数字、单位、列名取值），
     * 用户问"准确度是多少"时这些内容能帮上匹配。
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (caption != null && !caption.isBlank()) {
            sb.append(caption).append('\n');
        }
        sb.append(String.join(" | ", headers)).append('\n');

        int sample = Math.min(rows.size(), 5);
        for (int i = 0; i < sample; i++) {
            sb.append(String.join(" | ", rows.get(i))).append('\n');
        }
        if (rows.size() > sample) {
            sb.append("（共 ").append(rows.size()).append(" 行）");
        }
        return sb.toString().trim();
    }
}
