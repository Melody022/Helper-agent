package com.jixiejia.agent.router;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiConversation;
import com.jixiejia.agent.persistence.entity.ai.AiMessage;
import com.jixiejia.agent.persistence.mapper.ai.AiConversationMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆的读写。M4 阶段先用 ai_message / ai_conversation 落库；
 * M5 引入 LangGraph4j checkpoint 后，这里继续承担"给路由链看的短期上下文"这一职责
 * （checkpoint 管的是图状态，这里管的是人能直接查的消息流水）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationMemory {

    private final AiMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;

    /** 取最近 n 条消息，按时间正序返回。 */
    public List<AiMessage> recentMessages(String conversationId, int limit) {
        if (conversationId == null) {
            return List.of();
        }
        List<AiMessage> desc = messageMapper.selectList(
                Wrappers.<AiMessage>lambdaQuery()
                        .eq(AiMessage::getConversationId, conversationId)
                        .orderByDesc(AiMessage::getId)
                        .last("limit " + Math.max(1, limit)));
        List<AiMessage> asc = new ArrayList<>(desc);
        java.util.Collections.reverse(asc);
        return asc;
    }

    /** 最近 n 条用户消息的原文，按时间正序。 */
    public List<String> recentUserTexts(String conversationId, int limit) {
        List<String> users = recentMessages(conversationId, Math.max(1, limit) * 6).stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(AiMessage::getContent)
                .filter(c -> c != null && !c.isBlank())
                .toList();
        int from = Math.max(0, users.size() - limit);
        return users.subList(from, users.size());
    }

    /** 拼成给模型看的"最近对话"文本。没有历史时返回 null。 */
    public String recentTurnsAsText(String conversationId, int rounds) {
        List<AiMessage> messages = recentMessages(conversationId, rounds * 2);
        if (messages.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (AiMessage m : messages) {
            String who = "user".equals(m.getRole()) ? "用户" : "助手";
            String content = m.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.length() > 200) {
                content = content.substring(0, 200) + "…";
            }
            sb.append(who).append("：").append(content).append("\n");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 记录一条用户消息。 */
    public void saveUserMessage(String conversationId, String content) {
        save(conversationId, "user", content, null, null, null, null);
    }

    /**
     * 取最近若干轮对话，转成 Spring AI 的消息列表，供 Agent 构图时作为历史。
     *
     * <p>调用方必须在保存本轮用户消息<b>之前</b>调用，否则当前这句会被当成历史重复一次。
     */
    public List<Message> recentMessagesForModel(String conversationId, int rounds) {
        List<AiMessage> rows = recentMessages(conversationId, Math.max(1, rounds) * 2);
        List<Message> result = new ArrayList<>(rows.size());
        for (AiMessage m : rows) {
            String content = m.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("user".equals(m.getRole())) {
                result.add(new UserMessage(content));
            } else if ("assistant".equals(m.getRole())) {
                result.add(new AssistantMessage(content));
            }
        }
        return result;
    }
    /** 记录一条助手消息，附带本轮的路由结果。 */
    public void saveAssistantMessage(String conversationId, String content, String intent,
                                     Double confidence, String agentKey, Integer latencyMs) {
        save(conversationId, "assistant", content, intent, confidence, agentKey, latencyMs);
    }

    private void save(String conversationId, String role, String content, String intent,
                      Double confidence, String agentKey, Integer latencyMs) {
        if (conversationId == null) {
            return;
        }
        try {
            AiMessage m = new AiMessage();
            m.setConversationId(conversationId);
            m.setRole(role);
            m.setContent(content);
            m.setIntent(intent);
            m.setConfidence(confidence == null ? null : BigDecimal.valueOf(confidence));
            m.setAgentKey(agentKey);
            m.setLatencyMs(latencyMs);
            messageMapper.insert(m);
        } catch (Exception e) {
            // 记忆落库失败不该打断对话
            log.warn("消息落库失败（不影响对话）：{}", e.toString());
        }
    }

    /** 确保会话行存在，并更新最近活跃时间。 */
    public void touchConversation(String conversationId, Long aiUserId, Long memberId,
                                  String lastIntent, String lastAgentKey) {
        if (conversationId == null) {
            return;
        }
        try {
            AiConversation existing = conversationMapper.selectOne(
                    Wrappers.<AiConversation>lambdaQuery()
                            .eq(AiConversation::getConversationId, conversationId));

            if (existing == null) {
                AiConversation c = new AiConversation();
                c.setConversationId(conversationId);
                c.setUserId(aiUserId);
                c.setMemberId(memberId);
                c.setStatus("ACTIVE");
                c.setMessageCount(0);
                c.setLastIntent(lastIntent);
                c.setLastAgentKey(lastAgentKey);
                c.setLastActiveTime(LocalDateTime.now());
                conversationMapper.insert(c);
                return;
            }

            existing.setLastIntent(lastIntent);
            existing.setLastAgentKey(lastAgentKey);
            existing.setLastActiveTime(LocalDateTime.now());
            existing.setMessageCount((existing.getMessageCount() == null ? 0 : existing.getMessageCount()) + 2);
            conversationMapper.updateById(existing);
        } catch (Exception e) {
            log.warn("会话状态更新失败（不影响对话）：{}", e.toString());
        }
    }
}
