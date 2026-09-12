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

    /** 全角方括号（含其中的空格）：【1】/［1］ → [1] */
    private static final Pattern FULL_WIDTH =
            Pattern.compile("[【［]\\s*(\\d{1,3})\\s*[】］]");

    /** 资料在提示词里是 <1> 形态，模型偶尔照抄回来 → [1] */
    private static final Pattern ANGLE =
            Pattern.compile("<\\s*(\\d{1,3})\\s*>");

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
        String normalized = FULL_WIDTH.matcher(answer).replaceAll("[$1]");
        return ANGLE.matcher(normalized).replaceAll("[$1]");
    }

    /**
     * 这个编号是不是指向一条真实存在的依据。
     *
     * <p>前端据此决定"做成可点角标"还是"留成纯文本"。
     * 越界还做成链接的话，用户点开会看到**一份错的原件**——那比没有链接更伤信任。
     */
    public static boolean isValid(int index, int sourceCount) {
        return index >= 1 && index <= sourceCount;
    }
}
