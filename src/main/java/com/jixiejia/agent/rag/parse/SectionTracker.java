package com.jixiejia.agent.rag.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 逐行跟踪"当前处在文档的哪一节"，产出章节路径。
 *
 * <p>这是结构感知切分的关键件：切片要带上 {@code section_path}，靠的就是它。
 *
 * <p><b>两种章节标记，覆盖两类文档</b>：
 * <ol>
 *   <li><b>Markdown 标题</b>（{@code #}/{@code ##}）——人工整理的文档，
 *       {@code #} 的个数就是层级；</li>
 *   <li><b>条款编号</b>（{@code 5}、{@code 5.4}、{@code 5.4.4.1}）——国标、法规、手册这类。
 *       扫描件 OCR 出来的文本<b>没有 markdown 标记</b>，编号就是唯一可靠的结构锚点。</li>
 * </ol>
 *
 * <p><b>为什么层级要截断</b>：国标的编号层级能到 5 层（5.4.4.1.2），
 * 但越深越接近"这一句本身"，拿它当章节路径没有意义——还会让每个款各自成一节，
 * 回填时取不到上下文。所以只把前若干层当"章节"，更深的编号不更新路径。
 */
public final class SectionTracker {

    /**
     * 最多把几层<b>条款编号</b>当作"章节"。
     *
     * <p>国标编号能到 5 层（{@code 5.4.4.1.2}），但越深越接近"这一句本身"，
     * 拿它当章节路径没有意义——还会让每个款各自成一节，回填时取不到上下文。
     * 所以只认前两层（章、节），更深的编号只是条内序号。
     */
    private static final int MAX_CLAUSE_DEPTH = 2;

    /**
     * markdown 标题的层级上限。
     *
     * <p>和条款编号不同，markdown 的 {@code #} 是作者**手写**的层级，三级标题
     * （{@code ### 例外情况}）就是实实在在的一节，不该被截掉。
     * 留 6 是为了跟 markdown 规范对齐。
     */
    private static final int MAX_HEADING_DEPTH = 6;

    /** markdown 标题：{@code ## xxx} */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /**
     * 条款编号开头：{@code 5.4 润滑系统}、{@code 5.4.4.1 润滑系统应安全可靠}。
     *
     * <p>要求编号后面必须跟文字，避免把 "2022" 这种纯数字行、或 "3.5" 这类小数当章节。
     */
    private static final Pattern CLAUSE = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\s+(\\S.*)$");

    /** 栈里第 i 个元素 = 第 (i+1) 级章节的标题（含编号）。 */
    private final List<String> stack = new ArrayList<>();

    /**
     * 喂一行，返回这一行**属于**的章节路径。
     *
     * <p>如果这一行本身就是章节标记，先更新层级再返回——这样章节标题行归属于它自己开启的那一节。
     */
    public String accept(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return path();
        }

        Matcher h = HEADING.matcher(trimmed);
        if (h.matches()) {
            push(h.group(1).length(), h.group(2).trim(), MAX_HEADING_DEPTH);
            return path();
        }

        Matcher c = CLAUSE.matcher(trimmed);
        if (c.matches()) {
            String number = c.group(1);
            int depth = number.split("\\.").length;
            // 先按点分段数判断它像不像"章节"，再用 maxDepth 截断
            if (depth <= MAX_CLAUSE_DEPTH && !looksLikeYear(number)) {
                push(depth, trimmed, MAX_CLAUSE_DEPTH);
            }
        }
        return path();
    }

    /** 当前章节路径；还没识别出任何章节时返回空串。 */
    public String path() {
        return String.join(" > ", stack);
    }

    /** 文档结束时清空，避免跨文档串味（同一个 tracker 复用时要调）。 */
    void reset() {
        stack.clear();
    }

    /** 把层级压到 {@code depth}，再放上新的标题。 */
    private void push(int depth, String title, int maxDepth) {
        int level = Math.max(1, Math.min(depth, maxDepth));
        while (stack.size() >= level) {
            stack.remove(stack.size() - 1);
        }
        stack.add(clamp(title));
    }

    /** 章节标题太长时截断——它只是标签，不该撑爆数据库列。 */
    private static String clamp(String s) {
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /**
     * 这一串编号是不是"年份"。
     *
     * <p>国标正文里大量出现 "GB/T 25523—2022"、"2022 年" 这类行，
     * 若把 {@code 2022} 当成一级章节，整篇的章节路径会被冲掉。
     */
    private static boolean looksLikeYear(String number) {
        return number.length() == 4 && (number.startsWith("19") || number.startsWith("20"));
    }

    /**
     * 目录行里"标题和页码之间的连接符"。
     *
     * <p>中文文档最常用的是省略号 {@code ……}（U+2026），英文文档用点线 {@code ...}。
     * <b>一开始只写了点和中点，结果中文目录一条都识别不出来</b>——最常见的形态反而漏了。
     */
    private static final Pattern TOC_LINE =
            Pattern.compile("^.*[\\.·・…‥⋯]{3,}\\s*\\d+\\s*$");

    /**
     * 这一行是不是目录行。
     *
     * <p>目录要被**剔除**：它对问答没有任何实质内容，但字面上和正文章节标题高度相似，
     * 很容易在 Top-K 里占掉名额——和"同一知识拆成多个变体入库"是同一类问题。
     */
    public static boolean isTocLine(String line) {
        String t = line.trim();
        // 页码用点线/省略号连过来，这是目录最稳定的特征
        return TOC_LINE.matcher(t).matches();
    }
}
