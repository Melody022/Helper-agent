package com.jixiejia.agent.rag.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 markdown 里认出表格块。
 *
 * <p>LiteParse 会把版面里识别到的表格输出成标准 markdown 表格，所以这里做的是"识别 + 校验"，
 * 不需要自己去按坐标拼行列。
 *
 * <h2>为什么要校验，不能见到 <code>|</code> 就当表格</h2>
 *
 * <p>实测踩到的坑：国标 PDF 里的<b>公式</b>会被版面算法误判成表格。比如
 *
 * <pre>
 * | Ein回转 =∑ i=1∫ n | t2 Uin回转 Iin回转dt t1 | ………………(1) |
 * |---|---|---|
 * | Eout回转 =∑ i=1∫ n | t2 Uout回转 Iout回转dt t1 | ………………(2) |
 * </pre>
 *
 * <p>它有表头行、有合法的分隔行、列数也一致——<b>格式上完全合法</b>。
 * 如果照单全收，库里的"表格"会全是公式，检索命中它们只会污染答案。
 *
 * <p>所以额外加了几条判据，专门用来把这类假表格挡掉，见 {@link #isRealTable}。
 */
public final class MarkdownTableExtractor {

    /** 单元格内容超过这个长度就不像表格了（公式、整段文字会很长） */
    private static final int MAX_CELL_CHARS = 300;

    /**
     * 一张表至少要有这么多<b>数据行</b>。
     *
     * <p>这是挡住公式假表格最有效的一条：那类假表格无一例外只有 1 行"数据"。
     * 代价是"只有一行数据的真表格"会被漏掉——比把公式当表格收进来划算得多。
     */
    private static final int MIN_DATA_ROWS = 2;

    private MarkdownTableExtractor() {
    }

    /** 一个 markdown 表格块，含它在原文里的行号范围（便于剔除正文时定位）。 */
    public record Block(int startLine, int endLineExclusive, List<String> headers,
                        List<List<String>> rows) {
    }

    /**
     * 扫一遍 markdown，把<b>像真表格</b>的块按出现顺序返回。
     *
     * <p>只支持"整行竖线分隔"这一种写法（LiteParse 输出的就是这种）。
     */
    public static List<Block> extract(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            if (!isTableRow(lines.get(i))) {
                i++;
                continue;
            }
            // 从这一行开始，连续收集表格行
            int start = i;
            List<String> rows = new ArrayList<>();
            while (i < lines.size() && isTableRow(lines.get(i))) {
                rows.add(lines.get(i));
                i++;
            }
            Block block = tryBuild(start, rows);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    /** 判断一行是不是 markdown 表格行（以 | 开头结尾）。 */
    private static boolean isTableRow(String line) {
        String t = line.trim();
        return t.startsWith("|") && t.endsWith("|") && t.length() > 2;
    }

    /**
     * 把一段连续表格行拼成 Block；不合法（假表格）时返回 null。
     *
     * @param start 起始行号
     * @param raw   连续以 | 开头结尾的行
     */
    private static Block tryBuild(int start, List<String> raw) {
        // 至少要有：表头 + 分隔行 + MIN_DATA_ROWS 行数据
        if (raw.size() < MIN_DATA_ROWS + 2) {
            return null;
        }
        if (!isSeparatorRow(raw.get(1))) {
            return null;
        }

        List<String> headers = cells(raw.get(0));
        List<List<String>> data = new ArrayList<>();
        for (int i = 2; i < raw.size(); i++) {
            List<String> row = cells(raw.get(i));
            // 列数必须和表头一致，否则说明这是被拆散的残块或误判
            if (row.size() != headers.size()) {
                return null;
            }
            data.add(row);
        }

        if (!isRealTable(headers, data)) {
            return null;
        }
        return new Block(start, start + raw.size(), headers, data);
    }

    /** 分隔行形如 |---|---| —— 单元格必须是纯短横线（可带对齐冒号）。 */
    private static boolean isSeparatorRow(String line) {
        for (String cell : cells(line)) {
            if (!cell.matches(":?-{2,}:?")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 真表格 vs 公式假表格的判据。
     *
     * <p>只有列数一致是不够的——公式拼出来的表格格式完全合法，所以再加三条：
     * <ol>
     *   <li><b>数据行数够多</b>：假表格几乎都是 1 行；</li>
     *   <li><b>单元格不长</b>：公式单元格动辄上百字符；</li>
     *   <li><b>表头不像公式</b>：出现 <code>=</code>、<code>∑</code>、<code>∫</code>
     *       或结尾的编号 <code>(1)</code>，基本可以判定是从公式误识别来的。</li>
     * </ol>
     */
    private static boolean isRealTable(List<String> headers, List<List<String>> data) {
        if (data.size() < MIN_DATA_ROWS) {
            return false;
        }
        for (List<String> row : data) {
            for (String cell : row) {
                if (cell.length() > MAX_CELL_CHARS) {
                    return false;
                }
            }
        }
        for (String h : headers) {
            if (looksLikeFormula(h)) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeFormula(String cell) {
        if (cell.isEmpty()) {
            return false;
        }
        if (cell.contains("=") || cell.contains("∑") || cell.contains("∫")) {
            return true;
        }
        // 公式行尾常带编号，如 "…………………………(2)"
        return cell.matches(".*[(（]\\d+[)）]\\s*$") && cell.length() > 40;
    }

    /** 拆一行 markdown 表格的单元格，去掉首尾空的和两边竖线。 */
    private static List<String> cells(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String c : t.split("\\|", -1)) {
            out.add(c.trim());
        }
        return out;
    }

    /**
     * 把正文里的表格块剔除，只留普通文字。
     *
     * <p>表格已经从正文里独立出去了，如果再留在正文里被切一遍，
     * 那些残片照样会被检索命中——等于白做。
     */
    public static String removeTableBlocks(List<String> lines, List<Block> blocks) {
        boolean[] isTable = new boolean[lines.size()];
        for (Block b : blocks) {
            for (int i = b.startLine(); i < b.endLineExclusive() && i < lines.size(); i++) {
                isTable[i] = true;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (!isTable[i]) {
                sb.append(lines.get(i)).append('\n');
            }
        }
        return sb.toString();
    }
}
