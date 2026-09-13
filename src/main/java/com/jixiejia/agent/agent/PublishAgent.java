package com.jixiejia.agent.agent;

import com.jixiejia.agent.publish.graph.PublishGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 发布出租 / 求租。
 *
 * <p><b>本类现在只是个入口壳子</b>——流程本身在 {@link PublishGraph} 那张状态图上。
 * 这里只做两件必须在这里做的事：
 *
 * <ol>
 *   <li><b>决定"开新一轮"还是"恢复上一轮"</b>：问图这个会话是不是正挂在"等确认"上。
 *       挂起就是用户在回复确认摘要，得走 resume；否则是新的一轮。</li>
 *   <li><b>算抽取用的文本</b>：它依赖 Spring AI 的 {@code Message} 对象（不可序列化，
 *       进不了图的 state），所以只能在图外面算好、以纯字符串传进去。</li>
 * </ol>
 *
 * <p>为什么模型在这里管不着流程：发布是写操作。模型只负责"从用户话里抽字段"，
 * 有哪些字段、哪些必填、值怎么校验、什么时候能提交，全是代码决定的——
 * 抽错了也只会被校验拦下要求重填，不会把半截数据写进库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishAgent implements BizAgent {

    public static final String KEY = "PublishAgent";

    private final PublishGraph publishGraph;

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    public String reply(AgentContext context) {
        String text = context.userText() == null ? "" : context.userText().trim();
        String conversationId = context.conversationId();

        PublishGraph.PublishInput input = new PublishGraph.PublishInput(
                conversationId, context.userId(), context.memberId(),
                text, extractionText(context, text));

        return publishGraph.isAwaiting(conversationId)
                ? publishGraph.resume(input)
                : publishGraph.start(input);
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
}
