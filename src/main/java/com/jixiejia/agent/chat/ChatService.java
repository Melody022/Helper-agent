package com.jixiejia.agent.chat;

import com.jixiejia.agent.agent.AgentExecutor;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.graph.CompositeGraph;
import com.jixiejia.agent.rag.FlywheelService;
import com.jixiejia.agent.rag.KnowledgeAnswerService;
import com.jixiejia.agent.router.ConversationMemory;
import com.jixiejia.agent.router.MsgRouter;
import com.jixiejia.agent.router.RoutingDecision;
import com.jixiejia.agent.router.RoutingRequest;
import com.jixiejia.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 对话主流程：路由 → （短路或执行 Agent）→ 落库。
 *
 * <p>顺序上有两处不能颠倒：
 * <ol>
 *   <li><b>先取历史，再存本轮用户消息。</b>反过来的话，当前这句会被当成历史
 *       重复喂给模型，指代消解也会把"这个"指到它自己身上。</li>
 *   <li><b>先路由，后执行。</b>路由是纯判定，失败也要能落审计；
 *       Agent 执行可能很慢甚至超时，不该拖累判定与留痕。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 带进模型的历史轮数 */
    private static final int HISTORY_ROUNDS = 5;

    private static final String AGENT_MISSING_REPLY =
            "抱歉，这个功能暂时不可用，请稍后再试或回复\"转人工\"联系客服。";

    private static final String COMPOSITE_FAILED_REPLY =
            "抱歉，你这几个问题我都没能查到，可以分开一个个问，或者回复\"转人工\"联系客服。";

    private final MsgRouter msgRouter;
    private final AgentExecutor agentExecutor;
    private final ToolRegistry toolRegistry;
    private final ConversationMemory conversationMemory;
    private final CompositeGraph compositeGraph;
    private final KnowledgeAnswerService knowledgeAnswerService;
    private final FlywheelService flywheelService;

    /**
     * 一轮对话的产出。
     *
     * @param conversationId 会话标识（客户端没传时由服务端生成）
     * @param answer         给用户的回答
     * @param intent         识别出的意图
     * @param confidence     意图置信度
     * @param agentKey       实际处理的 Agent，短路时为 null
     * @param stage          路由终止于哪一步
     * @param shortCircuit   是否为短路回复（转人工/投诉/系统命令）
     */
    public record ChatResult(String conversationId, String answer, String intent,
                             double confidence, String agentKey, String stage,
                             boolean shortCircuit) {
    }

    /** 执行一轮对话。调用方需保证 userId 已登录（鉴权由拦截器负责）。 */
    public ChatResult chat(Long userId, Long memberId, String roleKey,
                           String conversationId, String message) {
        return chat(userId, memberId, roleKey, conversationId, message, null);
    }

    /**
     * 执行一轮对话，并在路由完成、开始执行 Agent 之前回调。
     *
     * <p>回调的用途是让 SSE 接口能在最耗时的 Agent 执行<b>之前</b>就把
     * "识别出什么意图、交给了哪个 Agent"推给前端。这一步很快（关键词命中时甚至不调模型），
     * 用户能立刻看到反馈，不用干等整段回答生成完。
     *
     * @param onRouted 路由完成后回调，可为 null
     */
    public ChatResult chat(Long userId, Long memberId, String roleKey,
                           String conversationId, String message,
                           java.util.function.Consumer<RoutingDecision> onRouted) {
        String convId = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId;

        // ① 历史要在保存本轮消息之前取
        List<Message> history = conversationMemory.recentMessagesForModel(convId, HISTORY_ROUNDS);

        // ② 路由（内部完成身份/命令/粘性/指代/意图/匹配/审计）
        RoutingRequest request = new RoutingRequest(convId, userId, memberId, roleKey, message);
        RoutingDecision decision = msgRouter.route(request);

        if (onRouted != null) {
            try {
                onRouted.accept(decision);
            } catch (Exception e) {
                // 回调失败（比如前端已断开）不该影响这轮对话本身
                log.debug("路由回调执行失败，忽略：{}", e.toString());
            }
        }

        // ③ 落用户消息，供下一轮当历史
        conversationMemory.saveUserMessage(convId, message);

        // ④ 短路：转人工/投诉/系统命令，直接用固定话术回
        if (decision.isShortCircuited()) {
            conversationMemory.saveAssistantMessage(convId, decision.reply(),
                    decision.intent() == null ? null : decision.intent().name(),
                    1.0, null, null);
            return new ChatResult(convId, decision.reply(),
                    decision.intent() == null ? null : decision.intent().name(),
                    1.0, null, decision.stage().code(), true);
        }

        // ⑤ 执行。三条路：平台规则走检索+证据闸，跨域走综合子图，其余走单 Agent。
        long startedAt = System.currentTimeMillis();

        String answer;
        String executedAgentKey;
        if (decision.intent() == Intent.KNOWLEDGE_QUERY) {
            // 平台规则单独一条路，不经过 ReAct Agent。
            // 因为这条道的硬要求是"资料不足就不许答"，而 Agent 里的模型有自主权，
            // 完全可能不去查资料、直接凭对同类平台的印象回答——那样证据闸就形同虚设。
            KnowledgeAnswerService.Answer knowledge =
                    knowledgeAnswerService.answer(decision.resolvedText(), convId);
            answer = knowledge.text();
            executedAgentKey = "Knowledge(检索+证据闸)";
            if (!knowledge.answered()) {
                // 没答上来意味着这是个知识盲区，记进飞轮等人工补
                log.debug("知识线未作答：{}", knowledge.note());
            }
        } else if (decision.isComposite()) {
            answer = runComposite(decision, roleKey);
            // 审计里记下本轮实际跑了哪些 Agent，用逗号分隔
            executedAgentKey = String.join(",", decision.targetAgents());
        } else {
            ToolCallback[] tools = toolRegistry.callbacksFor(decision.toolNames());
            answer = agentExecutor
                    .execute(decision.agentKey(), decision.resolvedText(), history, List.of(tools))
                    .orElseGet(() -> {
                        log.warn("路由选中了 Agent {}，但代码中没有对应实现", decision.agentKey());
                        return AGENT_MISSING_REPLY;
                    });
            executedAgentKey = decision.agentKey();
        }

        // 意图完全没识别出来也是一类知识盲区：用户问的东西系统根本没这套业务
        if (decision.intent() == Intent.UNKNOWN) {
            flywheelService.record(FlywheelService.SOURCE_LOW_CONFIDENCE,
                    convId, message, answer, decision.confidence());
        }

        int latency = (int) (System.currentTimeMillis() - startedAt);

        // ⑥ 落助手消息
        conversationMemory.saveAssistantMessage(convId, answer,
                decision.intent() == null ? null : decision.intent().name(),
                decision.confidence(), executedAgentKey, latency);

        return new ChatResult(convId, answer,
                decision.intent() == null ? null : decision.intent().name(),
                decision.confidence(), executedAgentKey, decision.stage().code(), false);
    }

    /** 跨域：交给综合子图并行跑各域再汇总。 */
    private String runComposite(RoutingDecision decision, String roleKey) {
        CompositeGraph.CompositeResult result = compositeGraph.run(
                decision.resolvedText(), roleKey, decision.targetAgents());

        if (result.answer() == null || result.answer().isBlank()) {
            log.warn("跨域综合未产出回答，目标域={}", result.agentKeys());
            return COMPOSITE_FAILED_REPLY;
        }
        return result.answer();
    }
}
