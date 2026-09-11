package com.jixiejia.agent.router;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import com.jixiejia.agent.llm.PromptLibrary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 第 ④ 步：指代消解。把"这个多少钱""那台呢""有便宜点的吗"补成完整问题。
 *
 * <p>为什么必须做：第 ⑤ 步的意图分类和第 ⑥ 步的工具填参，看到的都是这一句文本。
 * 如果直接把"那台呢"丢过去，模型无从判断"那台"是哪台，只能瞎猜或者反问。
 *
 * <p>实现上用小模型改写而不是字符串拼接：拼接（把上一轮问题贴到这句前面）会让分类器
 * 看到两个问题，很可能按<b>上一轮</b>那句去分类，等于把新意图吃掉。
 * 改写才是把指代补全成本句自身的意思。
 *
 * <p>改写失败（模型不可用/超时）时退回原文，并在 note 里记下，
 * 让审计日志能区分"没做消解"和"做了但没必要"。指代消解失败不该阻断对话，
 * 最坏情况就是这一轮回答得含糊一点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceResolver {

    /**
     * 触发消解的指代词。这里只收<b>真正需要回指</b>的词——"这个""它""那台"，
     * 不依赖上文就说不清指的是什么。
     *
     * <p>刻意<b>不</b>收"还有吗""便宜点""换一个"这类：它们是追问，不是指代，
     * 字面本身就表达了完整意思，该由会话粘性兜住（见 {@link MsgRouter} 第 ③ 步），
     * 放进这里只会让每轮追问都白跑一次模型。
     */
    private static final Set<String> REFERENTIAL_WORDS = Set.of(
            "这个", "那个", "这些", "那些", "这种", "那种",
            "这台", "那台", "这几台", "那几台", "它", "它们", "上面说的", "刚才说的");



    private final LlmClients clients;
    private final ModelCaller modelCaller;
    private final PromptLibrary prompts;

    /** 改写超时。本地小模型首次调用要加载模型，给足余量 */
    private static final long TIMEOUT_MS = 30_000L;

    @Value("${routing.reference.enabled:true}")
    private boolean enabled;

    /**
     * @param text        消解后的文本（未消解时等于原文）
     * @param resolved    是否真的做了改写
     * @param note        说明，用于审计
     */
    public record Resolution(String text, boolean resolved, String note) {

        static Resolution untouched(String text) {
            return new Resolution(text, false, null);
        }
    }

    /**
     * 判断并执行指代消解。
     *
     * @param text        用户当前这句
     * @param recentTurns 最近几轮对话文本，来自 {@link ConversationMemory#recentTurnsAsText}
     */
    public Resolution resolve(String text, String recentTurns) {
        if (!enabled || text == null || text.isBlank()) {
            return Resolution.untouched(text);
        }
        if (recentTurns == null || recentTurns.isBlank()) {
            // 没有历史可依据，消解无从谈起
            return Resolution.untouched(text);
        }
        if (!needsResolution(text)) {
            return Resolution.untouched(text);
        }

        String user = "对话历史：\n" + recentTurns + "\n用户最后一句：" + text;
        // 本地小模型冷启动慢，改写这种小任务也别用默认的 8 秒
        String rewritten = modelCaller.call(clients.small(), prompts.get("reference-resolution"),
                user, TIMEOUT_MS);

        if (rewritten == null || rewritten.isBlank()) {
            log.debug("指代消解改写失败，按原文处理：{}", text);
            return new Resolution(text, false, "检测到指代但改写失败，已按原文处理");
        }

        String cleaned = cleanup(rewritten);
        if (cleaned.isBlank() || cleaned.equals(text)) {
            return new Resolution(text, false, "检测到指代但无需改写");
        }

        log.debug("指代消解：{} -> {}", text, cleaned);
        return new Resolution(cleaned, true, "指代消解：" + text + " → " + cleaned);
    }

    /** 是否包含指代词。 */
    public boolean needsResolution(String text) {
        if (text == null) {
            return false;
        }
        for (String word : REFERENTIAL_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 模型可能不听话地加上引号、前缀或换行，这里清一遍。 */
    private static String cleanup(String raw) {
        String s = raw.trim();
        // 只取第一行：模型偶尔会先给一句解释
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl).trim();
        }
        if (s.length() > 1
                && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("「") && s.endsWith("」")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        for (String prefix : List.of("改写后：", "改写：", "答：", "结果：")) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).trim();
            }
        }
        return s;
    }
}
