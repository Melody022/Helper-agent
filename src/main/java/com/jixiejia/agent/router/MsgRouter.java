package com.jixiejia.agent.router;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.classify.IntentClassifier;
import com.jixiejia.agent.classify.IntentResult;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 消息路由链：① 身份 → ② 系统命令 → ③ 会话粘性 → ④ 指代消解 → ⑤ 三层意图 → ⑥ Agent 匹配 → ⑦ 审计。
 *
 * <p>整体设计取向是<b>确定性优先</b>：能靠规则、缓存、白名单判定的，一律不交给模型。
 * 模型只在第 ⑤ 步参与，且它的输出还要过一遍枚举校验。这样即使模型乱答，
 * 最坏也只是落进兜底 Agent，不会触发越权动作或错误路由。
 *
 * <p>关于第 ③ 步的实现取向：粘性<b>不是</b>在分类之前无条件短路。原因是如果先短路，
 * 就永远没机会发现"用户其实换话题了"。所以这里的做法是——先照常分类，
 * 再拿粘性结果做仲裁：
 * <ul>
 *   <li>本轮分类置信度不足（或判为 UNKNOWN）时，沿用上一轮 Agent，保证对话连贯；</li>
 *   <li>本轮分类高置信且指向别的领域时，切过去（对应方案里"高置信不同意图自动切换"）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MsgRouter {

    /** 投诉的安托管话术：只给选项，不替用户做决定，也不自动建单 */
    private static final String COMPLAINT_REPLY = """
            很抱歉给你带来了不好的体验，你的反馈我已经记录下来了。
            你可以选择：
            1. 回复"转人工"，我帮你转接人工客服进一步处理；
            2. 直接说明具体情况（涉及哪台设备、哪个订单、什么时候），我一并转给客服跟进。""";

    /** 转人工入队话术 */
    private static final String HANDOFF_REPLY = """
            好的，正在为你转接人工客服，请稍候。
            你也可以先留言说明具体问题，客服接入后能直接看到，省去重复描述。""";

    /** 注册表里一个可用 Agent 都没有时的兜底话术（正常运维下不该出现） */
    private static final String NO_AGENT_REPLY =
            "抱歉，助手当前不可用，请稍后再试或回复\"转人工\"联系客服。";

    private final AiUserMapper userMapper;
    private final CommandHandler commandHandler;
    private final StickySessionStore stickySessionStore;
    private final ConversationMemory conversationMemory;
    private final ReferenceResolver referenceResolver;
    private final IntentClassifier intentClassifier;
    private final AgentRegistry agentRegistry;
    private final AuditService auditService;

    /** 意图置信度达到多少才允许"甩开粘性、切换到别的 Agent" */
    @Value("${routing.sticky.switch-confidence:0.7}")
    private double stickySwitchConfidence;

    /**
     * 执行一次路由。本方法只做判定，不执行 Agent、不落消息——
     * 消息的落库与图执行由 M5 的对话服务负责，路由层保持无副作用便于单独回归。
     */
    public RoutingDecision route(RoutingRequest request) {
        long start = System.currentTimeMillis();
        try {
            return doRoute(request, start);
        } catch (Exception e) {
            log.error("路由链异常", e);
            auditService.recordError(request, e.toString(), elapsed(start));
            return RoutingDecision.shortCircuit(RouteStage.ERROR, Intent.UNKNOWN,
                    "抱歉，系统出了点问题，请稍后再试或回复\"转人工\"。", "路由链异常：" + e);
        }
    }

    private RoutingDecision doRoute(RoutingRequest request, long start) {
        String message = request.message() == null ? "" : request.message().trim();

        // ---------- ① 身份检查 ----------
        Optional<RoutingDecision> identityBlock = checkIdentity(request);
        if (identityBlock.isPresent()) {
            RoutingDecision d = identityBlock.get();
            auditService.record(request, d.stage(), d.intent(), d.confidence(), d.classifyLayer(),
                    null, Set.of(), d.reason(), elapsed(start));
            return d;
        }

        // ---------- ② 系统命令 ----------
        Optional<CommandHandler.CommandResult> command =
                commandHandler.handle(message, request.conversationId());
        if (command.isPresent()) {
            RoutingDecision d = RoutingDecision.shortCircuit(RouteStage.COMMAND, Intent.UNKNOWN,
                    command.get().reply(), "命中系统命令 " + command.get().command());
            auditService.record(request, d.stage(), d.intent(), d.confidence(), d.classifyLayer(),
                    null, Set.of(), d.reason(), elapsed(start));
            return d;
        }

        // ---------- ③ 会话粘性（先读出来，最后仲裁用） ----------
        Optional<StickySessionStore.StickySession> sticky =
                stickySessionStore.find(request.conversationId());

        // ---------- ④ 指代消解 ----------
        String recentTurns = conversationMemory.recentTurnsAsText(request.conversationId(), 5);
        ReferenceResolver.Resolution resolution = referenceResolver.resolve(message, recentTurns);
        String resolvedText = resolution.text();

        // ---------- ⑤ 三层意图分类 ----------
        IntentResult intent = intentClassifier.classify(resolvedText, recentTurns);
        log.debug("意图分类：{} conf={} layer={}", intent.intent(), intent.confidence(), intent.layer());

        // 短路意图：转人工 / 投诉，不进 Agent
        if (intent.intent() == Intent.HANDOFF) {
            RoutingDecision d = RoutingDecision.shortCircuit(RouteStage.HANDOFF, intent.intent(),
                    HANDOFF_REPLY, "意图判为转人工");
            afterRoute(request, intent, d, Set.of(), d.reason(), start);
            return d;
        }
        if (intent.intent() == Intent.COMPLAINT) {
            RoutingDecision d = RoutingDecision.shortCircuit(RouteStage.COMPLAINT, intent.intent(),
                    COMPLAINT_REPLY, "意图判为投诉");
            afterRoute(request, intent, d, Set.of(), d.reason(), start);
            return d;
        }

        // ---------- ⑥ Agent 匹配（含粘性仲裁） ----------
        boolean reuseSticky = shouldReuseSticky(intent, sticky);
        String roleKey = request.effectiveRoleKey();

        Optional<AgentRegistry.AgentMatch> matched = reuseSticky
                ? agentRegistry.byKey(sticky.get().agentKey(), roleKey)
                        .or(() -> agentRegistry.match(intent.intent(), roleKey))
                : agentRegistry.match(intent.intent(), roleKey);

        // 兜底链：意图匹配不到（如 CROSS_DOMAIN 这类）就退到 GeneralAgent；
        // 连兜底 Agent 都被停用了才算真的无人可用。
        AgentRegistry.AgentMatch match = matched
                .or(() -> agentRegistry.fallback(roleKey))
                .orElse(null);

        if (match == null) {
            RoutingDecision d = RoutingDecision.shortCircuit(RouteStage.ERROR, intent.intent(),
                    NO_AGENT_REPLY, "Agent 注册表中没有可用 Agent");
            afterRoute(request, intent, d, Set.of(), d.reason(), start);
            return d;
        }

        RouteStage stage = reuseSticky ? RouteStage.STICKY : RouteStage.EXECUTE;

        String reason = buildReason(resolution, intent, reuseSticky, sticky.orElse(null));

        RoutingDecision decision = new RoutingDecision(
                stage,
                intent.intent(),
                intent.confidence(),
                intent.layer(),
                match.agentKey(),
                match.toolNames(),
                resolvedText,
                null,
                reason);

        afterRoute(request, intent, decision, match.toolNames(), reason, start);
        return decision;
    }

    /** ① 身份：账号被停用直接拦下；匿名访问按最小权限 USER 放行。 */
    private Optional<RoutingDecision> checkIdentity(RoutingRequest request) {
        if (request.aiUserId() == null) {
            // 客服/导购场景本来就允许游客提问，匿名不是异常
            return Optional.empty();
        }
        AiUser user = userMapper.selectOne(
                Wrappers.<AiUser>lambdaQuery().eq(AiUser::getId, request.aiUserId()));
        if (user == null) {
            return Optional.of(RoutingDecision.shortCircuit(RouteStage.IDENTITY, Intent.UNKNOWN,
                    "账号不存在或已注销，请重新登录。", "账号不存在：" + request.aiUserId()));
        }
        if ("1".equals(user.getStatus())) {
            return Optional.of(RoutingDecision.shortCircuit(RouteStage.IDENTITY, Intent.UNKNOWN,
                    "该账号已被停用，如有疑问请联系平台客服。", "账号已停用：" + request.aiUserId()));
        }
        return Optional.empty();
    }

    /**
     * 是否沿用上一轮的 Agent。
     *
     * <p>判据是"本轮分类够不够有把握"：没把握就跟着上一轮走，有把握且换了领域就切。
     * 注意意图本身也需要与旧 Agent 的能力相容，否则等于把一个查询意图硬塞给不相干的 Agent。
     */
    private boolean shouldReuseSticky(IntentResult intent,
                                      Optional<StickySessionStore.StickySession> sticky) {
        if (sticky.isEmpty() || sticky.get().agentKey() == null) {
            return false;
        }
        if (intent.intent() == Intent.UNKNOWN) {
            return true;
        }
        if (intent.confidence() >= stickySwitchConfidence) {
            return false;
        }
        return true;
    }

    private String buildReason(ReferenceResolver.Resolution resolution, IntentResult intent,
                               boolean reuseSticky, StickySessionStore.StickySession sticky) {
        StringBuilder sb = new StringBuilder();
        if (resolution.note() != null) {
            sb.append(resolution.note()).append("；");
        }
        sb.append("意图=").append(intent.intent())
                .append("(").append(intent.confidence()).append("/").append(intent.layer().code()).append(")")
                .append("；");
        if (reuseSticky && sticky != null) {
            sb.append("沿用会话粘性 Agent=").append(sticky.agentKey());
        } else if (sticky != null) {
            sb.append("高置信切换，原粘性 Agent=").append(sticky.agentKey());
        } else {
            sb.append("按意图匹配 Agent");
        }
        return sb.toString();
    }

    /** 路由成功后的收尾：写粘性、更新会话、落审计。 */
    private void afterRoute(RoutingRequest request, IntentResult intent, RoutingDecision decision,
                            Set<String> toolNames, String reason, long start) {
        String agentKey = decision.agentKey();
        if (agentKey != null) {
            stickySessionStore.save(request.conversationId(), agentKey, intent.intent());
            conversationMemory.touchConversation(request.conversationId(), request.aiUserId(),
                    request.memberId(), intent.intent().name(), agentKey);
        }
        auditService.record(request, decision.stage(), intent, agentKey, toolNames, reason, elapsed(start));
    }

    private static int elapsed(long start) {
        return (int) (System.currentTimeMillis() - start);
    }
}
