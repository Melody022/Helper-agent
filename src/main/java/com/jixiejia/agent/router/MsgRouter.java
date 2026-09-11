package com.jixiejia.agent.router;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.classify.ClassifyLayer;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.classify.IntentClassifier;
import com.jixiejia.agent.classify.IntentResult;
import com.jixiejia.agent.classify.KeywordWeightClassifier;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 消息路由链：① 身份 → ② 命令 → ③ 粘性 → ④ 指代消解 → ⑤ 三层意图 → ⑥ Agent 匹配 → ⑦ 审计。
 * <p>整条链的取向是<b>确定性优先 + 从便宜到贵</b>：能靠规则、缓存、白名单判定的绝不交给模型；
 * 模型只在第 ⑤ 步参与，且它的输出还要过一遍枚举校验。这样即使模型乱答，
 * 最坏也只是落进兜底 Agent，不会触发越权动作或错误路由。
 *
 * <p>第 ③ 步是整个设计里最省成本的一环。它先做一次<b>零成本的关键词扫描</b>，
 * 再据此决定要不要惊动模型：
 * <ul>
 *   <li>关键词命中明确意图 X —— 和粘性里的意图比：一样就继续沿用，不一样立刻切换。
 *       这一步完全不调模型。</li>
 *   <li>关键词没命中（"那这个呢""多少钱"这种半截话）—— 只要粘性还在，
 *       就直接沿用上一轮的 Agent，<b>同样不调模型</b>。追问本来就不该重新分类。</li>
 *   <li>只有"关键词没命中 且 没有粘性"才升级到第 ④⑤ 步叫模型。</li>
 * </ul>
 * 换句话说：粘性不是"信任上一轮"，而是"用免费的关键词扫描先确认没换话题，
 * 确认不了再交给粘性兜底"。
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
    private final KeywordWeightClassifier keywordClassifier;
    private final IntentClassifier intentClassifier;
    private final AgentRegistry agentRegistry;
    private final AuditService auditService;

    /** 单轮跨域最多并行跑几个域，与 CompositeGraph 读同一个配置项 */
    @Value("${routing.cross-domain.max-agents:3}")
    private int crossDomainMaxAgents;

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
        String roleKey = request.effectiveRoleKey();

        // ---------- ① 身份检查：必须已登录 ----------
        Optional<RoutingDecision> identityBlock = checkIdentity(request);
        if (identityBlock.isPresent()) {
            return auditAndReturn(request, identityBlock.get(), start);
        }

        // ---------- ② 系统命令 ----------
        Optional<CommandHandler.CommandResult> command =
                commandHandler.handle(message, request.conversationId());
        if (command.isPresent()) {
            return auditAndReturn(request,
                    RoutingDecision.shortCircuit(RouteStage.COMMAND, Intent.UNKNOWN,
                            command.get().reply(), "命中系统命令 " + command.get().command()),
                    start);
        }

        // ---------- ③ 粘性 + 零成本关键词扫描 ----------
        Optional<StickySessionStore.StickySession> sticky =
                stickySessionStore.find(request.conversationId());
        Optional<IntentResult> byKeyword = keywordClassifier.classify(message);

        // 跨域判定必须排在单域路由之前。否则它会走进 AgentRegistry 的"能力交集最多者胜"，
        // 被交集最多的那个 Agent 整条吃掉——用户问三件事，只答一件，且看不出错在哪。
        Optional<List<String>> crossAgents = detectCrossDomain(message, roleKey);
        if (crossAgents.isPresent()) {
            RoutingDecision decision = new RoutingDecision(
                    RouteStage.COMPOSITE, Intent.CROSS_DOMAIN, 0.9, ClassifyLayer.KEYWORD,
                    null, new LinkedHashSet<>(crossAgents.get()), Set.of(),
                    message, null,
                    "命中 " + crossAgents.get().size() + " 个领域，走跨域综合子图：" + crossAgents.get());
            log.debug("跨域命中：{} -> {}", message, crossAgents.get());
            return auditAndReturn(request, decision, start);
        }

        if (byKeyword.isPresent()) {
            return routeByKnownIntent(request, byKeyword.get(), sticky, roleKey, start);
        }

        // 关键词没命中：模糊追问优先用粘性兜住，避免白跑一次模型
        if (sticky.isPresent()) {
            Optional<AgentRegistry.AgentMatch> reuse =
                    agentRegistry.byKey(sticky.get().agentKey(), roleKey);
            if (reuse.isPresent()) {
                Intent stickyIntent = Intent.parse(sticky.get().intent());
                RoutingDecision decision = new RoutingDecision(
                        RouteStage.STICKY, stickyIntent, 1.0, null,
                        reuse.get().agentKey(), Set.of(), reuse.get().toolNames(), message,
                        null, "关键词未命中，沿用会话粘性 Agent=" + reuse.get().agentKey());
                log.debug("粘性兜底：{} -> {}", message, reuse.get().agentKey());
                return auditAndReturn(request, decision, start);
            }
            // 粘性里的 Agent 已被停用或删除，只能重新分类
            log.debug("粘性 Agent {} 已不可用，重新分类", sticky.get().agentKey());
        }

        // ---------- ④ 指代消解（只有真要叫模型时才做）----------
        String recentTurns = conversationMemory.recentTurnsAsText(request.conversationId(), 5);
        ReferenceResolver.Resolution resolution = referenceResolver.resolve(message, recentTurns);

        // ---------- ⑤ 三层意图分类 ----------
        IntentResult intent = intentClassifier.classify(resolution.text(), recentTurns);
        log.debug("意图分类：{} conf={} layer={}", intent.intent(), intent.confidence(), intent.layer());

        if (intent.intent().isShortCircuit()) {
            return auditAndReturn(request, shortCircuitFor(intent), start);
        }

        // 模型也可能判出跨域（关键词层漏掉的说法）。这条路径必须单独处理——
        // 直接丢进下面的单 Agent 匹配，会被"能力交集最多者胜"整条吃掉，
        // 用户问的三件事只答一件，且看不出错在哪。
        if (intent.intent() == Intent.CROSS_DOMAIN) {
            List<String> agents = resolveCrossAgents(intent, resolution.text(), roleKey);
            if (agents.size() >= 2) {
                RoutingDecision composite = new RoutingDecision(
                        RouteStage.COMPOSITE, Intent.CROSS_DOMAIN, intent.confidence(), intent.layer(),
                        null, new LinkedHashSet<>(agents), Set.of(), resolution.text(), null,
                        "模型判为跨域，涉及 " + agents.size() + " 个领域：" + agents);
                log.debug("模型判定跨域：{} -> {}", message, agents);
                return auditAndReturn(request, composite, start);
            }
            if (agents.size() == 1) {
                // 判成跨域但只有一个领域站得住，按单域走，别硬凑综合
                AgentRegistry.AgentMatch single = matchAgent(intent.intent(), roleKey, agents.get(0));
                if (single == null) {
                    return auditAndReturn(request, RoutingDecision.shortCircuit(
                            RouteStage.ERROR, Intent.CROSS_DOMAIN, NO_AGENT_REPLY, "无可用 Agent"), start);
                }
                RoutingDecision decision = new RoutingDecision(
                        RouteStage.EXECUTE, Intent.CROSS_DOMAIN, intent.confidence(), intent.layer(),
                        single.agentKey(), Set.of(), single.toolNames(), resolution.text(), null,
                        "模型判为跨域但只解析出 1 个领域，按单域处理：" + single.agentKey());
                return auditAndReturn(request, decision, start);
            }
            // 一个领域都解析不出来，落到下面的兜底匹配
            log.debug("模型判为跨域但解析不出任何领域，退回兜底匹配");
        }

        // ---------- ⑥ Agent 匹配 ----------
        AgentRegistry.AgentMatch match = matchAgent(intent.intent(), roleKey, null);
        if (match == null) {
            return auditAndReturn(request,
                    RoutingDecision.shortCircuit(RouteStage.ERROR, intent.intent(),
                            NO_AGENT_REPLY, "Agent 注册表中没有可用 Agent"), start);
        }

        String reason = (resolution.note() != null ? resolution.note() + "；" : "")
                + "意图=" + intent.intent() + "(" + intent.confidence() + "/" + intent.layer().code() + ")"
                + "；判定：" + intent.reason()
                + "；没有粘性可用，按意图匹配 Agent";

        RoutingDecision decision = new RoutingDecision(
                RouteStage.EXECUTE, intent.intent(), intent.confidence(), intent.layer(),
                match.agentKey(), Set.of(), match.toolNames(), resolution.text(), null, reason);

        return auditAndReturn(request, decision, start);
    }

    /**
     * 关键词已经给出明确意图时的分支。
     *
     * <p>此时不需要任何模型：意图是确定的，只需要判断该不该沿用粘性里的 Agent。
     * 判据很直接——意图和上一轮一样就继续用，不一样就切。
     */
    private RoutingDecision routeByKnownIntent(RoutingRequest request, IntentResult keywordIntent,
                                               Optional<StickySessionStore.StickySession> sticky,
                                               String roleKey, long start) {
        Intent intent = keywordIntent.intent();

        // 转人工 / 投诉：关键词命中即短路，不进 Agent
        if (intent.isShortCircuit()) {
            return auditAndReturn(request, shortCircuitFor(keywordIntent), start);
        }

        boolean sameAsSticky = sticky.isPresent()
                && intent.name().equals(sticky.get().intent());

        String preferredAgent = sameAsSticky ? sticky.get().agentKey() : null;
        AgentRegistry.AgentMatch match = matchAgent(intent, roleKey, preferredAgent);
        if (match == null) {
            return auditAndReturn(request,
                    RoutingDecision.shortCircuit(RouteStage.ERROR, intent,
                            NO_AGENT_REPLY, "Agent 注册表中没有可用 Agent"), start);
        }

        RouteStage stage = sameAsSticky ? RouteStage.STICKY : RouteStage.EXECUTE;
        String reason = sameAsSticky
                ? "关键词命中 " + intent + "，与会话粘性一致，继续沿用 " + match.agentKey()
                : (sticky.isPresent()
                        ? "关键词命中 " + intent + "，与粘性话题不同，切换到 " + match.agentKey()
                        : "关键词命中 " + intent + "，匹配 " + match.agentKey());

        RoutingDecision decision = new RoutingDecision(
                stage, intent, keywordIntent.confidence(), keywordIntent.layer(),
                match.agentKey(), Set.of(), match.toolNames(), request.message(), null, reason);

        return auditAndReturn(request, decision, start);
    }

    /**
     * 跨域判定：这句话是否同时命中了多个<b>不同 Agent</b> 负责的领域。
     *
     * <p>三个刻意的取舍：
     * <ol>
     *   <li><b>只认高权重命中</b>。中权重词（"价格""设备"）跨域出现得太频繁，
     *       拿它触发会让这条昂贵路径天天误触发；</li>
     *   <li><b>按解析后的 Agent 去重计数，而不是按意图计数</b>。chuzu / qiuzu / demand
     *       三个意图都属于 RentalAgent，按意图数会把"有人要租吗、也有人要找活吗"
     *       判成跨域，然后把同一个 Agent 跑两遍；</li>
     *   <li><b>排除兜底 Agent</b>。兜底本就不代表任何领域，把它算进去会让任意
     *       "没匹配上"的组合都变成跨域。</li>
     * </ol>
     *
     * @return 命中 ≥2 个 Agent 时返回它们（按关键词强弱排序、已截到上限），否则 empty
     */
    private Optional<List<String>> detectCrossDomain(String message, String roleKey) {
        if (keywordClassifier.highWeightIntents(message).size() < 2) {
            return Optional.empty();
        }
        List<String> agents = resolveCrossAgents(null, message, roleKey);
        return agents.size() >= 2 ? Optional.of(agents) : Optional.empty();
    }

    /**
     * 把跨域判定解析成"本轮要跑哪些 Agent"。两个来源取并集后按 Agent 去重：
     * <ul>
     *   <li>模型在 {@code domains} 里指出的领域（能覆盖关键词漏掉的说法）；</li>
     *   <li>关键词高权重命中的意图（模型漏说时补上）。</li>
     * </ul>
     *
     * <p>去重很关键：chuzu / qiuzu / demand 三个领域都由 RentalAgent 负责，
     * 不去重会把同一个 Agent 排进去跑两遍。
     *
     * @return 去重后的 Agent 列表，可能为空或只有 1 个（调用方据此决定退化成单域）
     */
    private List<String> resolveCrossAgents(IntentResult intent, String text, String roleKey) {
        Set<String> agents = new LinkedHashSet<>();

        if (intent != null && intent.hasDomains()) {
            for (String capability : intent.domains()) {
                if (agents.size() >= crossDomainMaxAgents) {
                    break;
                }
                Intent.byCapability(capability)
                        .flatMap(mapped -> agentRegistry.match(mapped, roleKey))
                        .filter(match -> !match.fallback())
                        .ifPresent(match -> agents.add(match.agentKey()));
            }
        }

        for (Intent hit : keywordClassifier.highWeightIntents(text)) {
            if (agents.size() >= crossDomainMaxAgents) {
                break;
            }
            agentRegistry.match(hit, roleKey)
                    .filter(match -> !match.fallback())
                    .ifPresent(match -> agents.add(match.agentKey()));
        }

        return List.copyOf(agents);
    }

    /** 短路意图（转人工 / 投诉）对应的决策。 */
    private RoutingDecision shortCircuitFor(IntentResult intent) {
        return switch (intent.intent()) {
            case HANDOFF -> RoutingDecision.shortCircuit(RouteStage.HANDOFF, intent.intent(),
                    HANDOFF_REPLY, "意图判为转人工");
            case COMPLAINT -> RoutingDecision.shortCircuit(RouteStage.COMPLAINT, intent.intent(),
                    COMPLAINT_REPLY, "意图判为投诉");
            default -> throw new IllegalArgumentException("不是短路意图：" + intent.intent());
        };
    }

    /**
     * 匹配 Agent。preferredAgentKey 非空时优先复用该 Agent（粘性场景），
     * 但它已被停用/删除时自动退回按意图重新匹配。
     */
    private AgentRegistry.AgentMatch matchAgent(Intent intent, String roleKey, String preferredAgentKey) {
        Optional<AgentRegistry.AgentMatch> preferred = preferredAgentKey == null
                ? Optional.empty()
                : agentRegistry.byKey(preferredAgentKey, roleKey);

        return preferred
                .or(() -> agentRegistry.match(intent, roleKey))
                .or(() -> agentRegistry.fallback(roleKey))
                .orElse(null);
    }

    /**
     * ① 身份：必须登录后才能使用。未登录、账号不存在、账号被停用，都在这里拦下。
     *
     * <p>之所以不像常见客服机器人那样允许游客提问：这个助手能查到设备卖家、会员等
     * 平台侧数据，也能代用户发起发布，匿名使用既没法做权限控制，也没法把发布落到具体会员头上。
     */
    private Optional<RoutingDecision> checkIdentity(RoutingRequest request) {
        if (request.aiUserId() == null) {
            return Optional.of(RoutingDecision.shortCircuit(RouteStage.IDENTITY, Intent.UNKNOWN,
                    "请先登录后再使用智能助手。", "未登录"));
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

    /** 路由收尾：写粘性、更新会话、落审计。 */
    private RoutingDecision auditAndReturn(RoutingRequest request, RoutingDecision decision, long start) {
        String agentKey = decision.agentKey();
        Intent intent = decision.intent();

        if (agentKey != null) {
            stickySessionStore.save(request.conversationId(), agentKey, intent);
            conversationMemory.touchConversation(request.conversationId(), request.aiUserId(),
                    request.memberId(), intent == null ? null : intent.name(), agentKey);
        }

        auditService.record(request, decision.stage(), intent, decision.confidence(),
                decision.classifyLayer(), agentKey, decision.toolNames(), decision.reason(), elapsed(start));

        return decision;
    }

    private static int elapsed(long start) {
        return (int) (System.currentTimeMillis() - start);
    }
}
