package com.jixiejia.agent.agent;

import com.jixiejia.agent.persistence.entity.ai.AiPublishRequest;
import com.jixiejia.agent.publish.PublishFormService;
import com.jixiejia.agent.publish.PublishTarget;
import com.jixiejia.agent.publish.PublishWriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布 Agent：走确定性工作流，不接 ReAct 图。
 *
 * <p>这是唯一一个<b>不交给模型自由发挥</b>的 Agent。原因很直接：发布是写操作。
 * 模型在这条链路上的职责被压到最小——只把用户话里的字段值抽出来；
 * 有哪些字段、值合不合法、什么时候能提交、什么时候真的落库，
 * 全部由代码决定。模型抽错了值只会被校验拦下要求重填，不会把半截数据写进库。
 *
 * <p>流程：
 * <pre>
 *   用户说要发布 → 新开草稿 → 逐轮抽字段 → 追问缺失项 → 字段齐了给确认摘要 + 令牌
 *   → 用户带令牌确认 → 落库（audit_status=0 待审核）
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishAgent implements BizAgent {

    public static final String KEY = "PublishAgent";

    /** 确认词。用户看完摘要说这些就是同意提交 */
    private static final Set<String> CONFIRM_WORDS = Set.of(
            "确认", "确定", "提交", "没问题", "可以", "对的", "是的", "没问题了");

    /** 取消词 */
    private static final Set<String> CANCEL_WORDS = Set.of(
            "取消", "算了", "不发了", "先不", "放弃");

    /** 确认令牌的样式：8 位大写字母数字 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\b[A-Z0-9]{8}\\b");

    private final PublishFormService formService;
    private final PublishWriteService writeService;

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    public String reply(AgentContext context) {
        String text = context.userText() == null ? "" : context.userText().trim();

        // 发布要落库、要挂到具体会员名下，没有身份的调用是走不通的。
        // 正常流程里 userId 由登录态提供，这里只是防御性拦截，
        // 避免直接抛一个数据库异常出来。
        if (context.userId() == null) {
            return "发布需要先登录。请登录后再让我帮你发布信息。";
        }

        try {
            AiPublishRequest draft = formService.findActiveDraft(context.conversationId());

            // ---------- 有草稿且在等确认 ----------
            if (draft != null && formService.isAwaitingConfirm(draft)) {
                return handlePendingConfirm(context, draft, text);
            }

            // ---------- 新起一条 ----------
            PublishTarget target = detectTarget(text, draft);
            if (target == null) {
                return "你想发布的是「出租」还是「求租」？\n"
                        + "  · 出租：把你的设备挂出去租给别人\n"
                        + "  · 求租：你这边需要机器，发个需求\n"
                        + "告诉我是哪种，我帮你填。";
            }

            boolean starting = draft == null || !target.table().equals(draft.getTargetTable());
            return formService.advance(context.userId(), context.conversationId(),
                    target, extractionText(context, text), starting).reply();

        } catch (Exception e) {
            log.error("发布流程执行失败", e);
            return "发布流程出了点问题，请稍后再试，或者回复\"转人工\"让客服帮你发布。";
        }
    }

    /** 处理"等待用户确认"这一轮。 */
    private String handlePendingConfirm(AgentContext context, AiPublishRequest draft, String text) {
        if (containsAny(text, CANCEL_WORDS)) {
            draft.setStatus("REJECTED");
            draft.setReviewNote("用户取消");
            formService.cancel(draft);
            return "好的，这条发布已经取消了。需要重新发布随时告诉我。";
        }

        if (!containsAny(text, CONFIRM_WORDS)) {
            // 不是确认也不是取消，当作对内容的补充或修改，继续推进表单
            PublishTarget target = PublishTarget.byTable(draft.getTargetTable());
            return formService.advance(context.userId(), context.conversationId(),
                    target, extractionText(context, text), false).reply();
        }

        // 是确认。校验令牌
        if (!formService.isTokenValid(draft)) {
            formService.expire(draft);
            return "这条发布请求超过时间没确认，我这边先作废了（过了有效期再提交会有风险）。\n"
                    + "要重新发布的话再跟我说一次就行。";
        }

        String provided = extractToken(text);
        if (provided != null && !provided.equalsIgnoreCase(draft.getConfirmToken())) {
            // 用户抄错了令牌——说明他可能在看另一条发布的摘要
            return "这个确认码对不上，请核对一下是不是复制错了。\n"
                    + "正确的确认码是 " + draft.getConfirmToken() + "。";
        }

        Map<String, Object> payload = formService.readPayload(draft);
        PublishWriteService.WriteResult result = writeService.submit(draft, payload);
        return result.message();
    }

    /**
     * 判断要发布的是出租还是求租。
     *
     * <p>优先看这句话里的词；说不清时沿用草稿的类型（用户可能只是在补充信息）。
     * 两个都判断不出来就返回 null，由调用方追问。
     */
    private PublishTarget detectTarget(String text, AiPublishRequest draft) {
        boolean wantsRent = text.contains("求租") || text.contains("找机器")
                || text.contains("找设备") || text.contains("找活") || text.contains("需要机器");
        boolean wantsLease = text.contains("出租") || text.contains("租出去")
                || text.contains("挂出去") || text.contains("往外租");

        if (wantsRent && !wantsLease) {
            return PublishTarget.QIUZU;
        }
        if (wantsLease && !wantsRent) {
            return PublishTarget.CHUZU;
        }
        // 说不清就沿用已有草稿的类型
        if (draft != null) {
            return PublishTarget.byTable(draft.getTargetTable());
        }
        return null;
    }

    private static boolean containsAny(String text, Set<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 抽取字段时看的文本 = 最近几轮用户消息 + 当前这句。
     *
     * <p>只看当前这一句的话，用户分多轮提供信息时，前面某轮被模型漏抽的字段
     * 就永久丢了——用户明明说过吨位，系统却反复追问，体验很差。
     * 带上最近几轮，漏掉的值下一轮还有机会被捡回来。
     *
     * <p>只取用户说的话，不取助手的回复：助手的确认摘要里含令牌和梳理过的文案，
     * 混进去可能被当成字段值抽出来。
     */
    private static String extractionText(AgentContext context, String current) {
        List<Message> history = context.history();
        if (history == null || history.isEmpty()) {
            return current;
        }

        List<String> userTexts = history.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank())
                .toList();

        // 只取最近 3 轮，太早的内容和当前发布关系不大，还占 token
        int from = Math.max(0, userTexts.size() - 3);
        if (from >= userTexts.size()) {
            return current;
        }

        StringBuilder sb = new StringBuilder();
        userTexts.subList(from, userTexts.size()).forEach(t -> sb.append(t).append('\n'));
        sb.append(current);
        return sb.toString();
    }

    /** 从"确认发布 A3F2B1C9"里把令牌抠出来。没写令牌时返回 null（允许只说"确认"）。 */
    private static String extractToken(String text) {
        Matcher m = TOKEN_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }
}
