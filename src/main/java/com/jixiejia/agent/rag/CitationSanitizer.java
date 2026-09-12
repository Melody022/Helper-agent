package com.jixiejia.agent.rag;

import java.util.regex.Pattern;

/**
 * 归一化答案正文里的引用角标写法。
 *
 * <p><b>它只做"改写写法"，不做"判断对错"。</b>编号是不是标在正确的结论后面，
 * 这里管不了——那要靠生成后的逐句校验（NLI），是下一步的事。这里只保证
 * 前端拿到的正文里，角标写法是统一的一种。
 *
 * <p>为什么必须归一化：提示词只能"要求"模型用 <code>[1]</code>，
 * 实测它会用全角方括号，也会把资料前的 <code>&lt;1&gt;</code> 标签照抄回来。
 * 与其在渲染层认五六种写法，不如在服务端收敛成一种——
 * <b>渲染层越简单，出安全问题的面就越小。</b>
 */
public final class CitationSanitizer {

    /** 半角与全角数字。模型可能整体切全角（【１】），只认 [0-9] 会漏。 */
    private static final String DIGITS = "[0-9０-９]";

    /** 全角方括号（含其中空格与全角数字）：【１】/［1］ → [1] */
    private static final Pattern FULL_WIDTH =
            Pattern.compile("[【［]\\s*(" + DIGITS + "{1,3})\\s*[】］]");

    /**
     * 半角方括号夹空格：模型可能写成 [ 1 ]，前端按 [0-9] 认不出。
     * 幂等：已经是 [1] 的再过一遍还是 [1]。
     */
    private static final Pattern HALF_WIDTH =
            Pattern.compile("\\[\\s*(" + DIGITS + "{1,3})\\s*\\]");

    /**
     * 资料在提示词里是 <1> 形态，模型偶尔照抄回来 → [1]。
     *
     * <p>代价：正文里字面的 <code>&lt;3&gt;</code>（用户上传的文档里真有这种写法）
     * 也会被一并改掉。这是<b>有意接受的取舍</b>——漏掉模型照抄的角标比误改一个
     * 孤立尖括号数字更伤，所以这里不做"是不是真角标"的甄别。
     */
    private static final Pattern ANGLE =
            Pattern.compile("<\\s*(" + DIGITS + "{1,3})\\s*>");

    private CitationSanitizer() {
    }

    /**
     * 把各种角标写法归一成半角方括号。
     *
     * <p>越界的编号（比如只有 5 条依据却写了 [9]）**原样保留**——
     * 是"留成纯文本"还是"做成可点链接"由渲染层按 {@link #isValid} 决定，
     * 这里不替它做删除决定。少一个角标事小，删错正文事大。
     */
    public static String normalize(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        String normalized = FULL_WIDTH.matcher(answer)
                .replaceAll(m -> "[" + toHalfWidthDigits(m.group(1)) + "]");
        normalized = ANGLE.matcher(normalized)
                .replaceAll(m -> "[" + toHalfWidthDigits(m.group(1)) + "]");
        return HALF_WIDTH.matcher(normalized)
                .replaceAll(m -> "[" + toHalfWidthDigits(m.group(1)) + "]");
    }

    /**
     * 这个编号是不是指向一条真实存在的依据。
     *
     * <p>前端据此决定"做成可点角标"还是"留成纯文本"。
     * 越界还做成链接的话，用户点开会看到**一份错的原件**——那比没有链接更伤信任。
     *
     * <p><b>这条规则有两份实现。</b>服务端目前只调 {@link #normalize}，越界判断
     * 实际是在前端 JS 的 <code>renderCitations</code> 里内联的，两端各自写着同一条
     * <code>1..sourceCount</code>。这里作为它的<b>权威表述</b>：改这条规则时，
     * 必须同步改前端那份镜像，否则两端会对"什么编号可点"给出不同的答案。
     */
    public static boolean isValid(int index, int sourceCount) {
        return index >= 1 && index <= sourceCount;
    }

    /**
     * 把捕获组里可能混入的全角数字（０-９）转成半角。
     *
     * <p>只放宽数字类不够：如果捕获到的是全角 １，直接拼出 [１]，前端按
     * <code>[0-9]</code> 照样认不出来，等于没修。所以这里把全角数字转回半角。
     */
    private static String toHalfWidthDigits(String digits) {
        StringBuilder halfWidth = new StringBuilder(digits.length());
        for (char c : digits.toCharArray()) {
            if (c >= '０' && c <= '９') {
                halfWidth.append((char) (c - '０' + '0'));
            } else {
                halfWidth.append(c);
            }
        }
        return halfWidth.toString();
    }
}
