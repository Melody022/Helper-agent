package com.jixiejia.agent.chat;

import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.rag.FlywheelService;
import com.jixiejia.agent.rag.KnowledgeAnswerService;
import com.jixiejia.agent.router.ConversationMemory;
import com.jixiejia.agent.router.RoutingDecision;
import com.jixiejia.agent.router.RoutingGraph;
import com.jixiejia.agent.router.RoutingRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 对话入口：把一轮请求交给路由状态图，再把结果落库。
 *
 * <p><b>本类已经很薄了</b>——路由、审计、三条执行路全在 {@link RoutingGraph} 上。
 * 留在这里的只有图的"外面"该做的事：生成会话 id、落消息、记飞轮、拼返回值。
 *
 * <p>顺序上有两处不能颠倒：
 * <ol>
 *   <li><b>先跑图，再落本轮用户消息。</b>图里的指代消解会读"最近几轮"，
 *       要是先把这句存进去，它就会把"这个"指到它自己身上。</li>
 *   <li><b>先路由，后执行。</b>这一点现在由图的形状保证了：
 *       审计节点在分派节点之前，执行永远发生在审计之后。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final RoutingGraph routingGraph;
    private final ConversationMemory conversationMemory;
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
     * @param sources        答案依据（仅知识线有），供前端溯源展示；其它线路为空
     */
    public record ChatResult(String conversationId, String answer, String intent,
                             double confidence, String agentKey, String stage,
                             boolean shortCircuit,
                             List<KnowledgeAnswerService.Source> sources) {

        /** 没有依据可展示时的便捷构造。 */
        public ChatResult(String conversationId, String answer, String intent,
                          double confidence, String agentKey, String stage, boolean shortCircuit) {
            this(conversationId, answer, intent, confidence, agentKey, stage, shortCircuit, List.of());
        }
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

        long startedAt = System.currentTimeMillis();

        // ① 图：判定 → 审计 → 执行，一条龙。onRouted 由分派节点在"审计完、执行前"触发
        RoutingRequest request = new RoutingRequest(convId, userId, memberId, roleKey, message);
        RoutingGraph.RoutingResult result = routingGraph.run(request, onRouted);
        RoutingDecision decision = result.decision();

        // ② 落本轮用户消息。放在图之后是有意的：图里的指代消解不该看到这一句
        conversationMemory.saveUserMessage(convId, message);

        String answer = result.answer();
        if (answer == null || answer.isBlank()) {
            // 短路回复没经过执行节点，回答就是决策里那句话
            answer = decision.reply() == null ? "" : decision.reply();
        }

        // 意图完全没识别出来也是一类知识盲区：用户问的东西系统根本没这套业务
        if (decision.intent() == Intent.UNKNOWN && !decision.isShortCircuited()) {
            flywheelService.record(FlywheelService.SOURCE_LOW_CONFIDENCE,
                    convId, message, answer, decision.confidence());
        }

        int latency = (int) (System.currentTimeMillis() - startedAt);

        // ③ 落助手消息
        conversationMemory.saveAssistantMessage(convId, answer,
                decision.intent() == null ? null : decision.intent().name(),
                decision.confidence(), result.executedAgentKey(), latency);

        return new ChatResult(convId, answer,
                decision.intent() == null ? null : decision.intent().name(),
                decision.confidence(), result.executedAgentKey(), decision.stage().code(),
                decision.isShortCircuited(), result.sources());
    }
}
