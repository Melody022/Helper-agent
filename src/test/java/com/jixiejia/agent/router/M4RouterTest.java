package com.jixiejia.agent.router;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.classify.ClassifyLayer;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.classify.IntentClassifier;
import com.jixiejia.agent.classify.IntentResult;
import com.jixiejia.agent.classify.KeywordWeightClassifier;
import com.jixiejia.agent.persistence.entity.ai.AiAuditLog;
import com.jixiejia.agent.persistence.entity.ai.AiConversation;
import com.jixiejia.agent.persistence.entity.ai.AiMessage;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiAuditLogMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiConversationMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiMessageMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import com.jixiejia.agent.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 路由链测试。
 *
 * <p>刻意关掉后两层模型与指代消解（见 {@code @SpringBootTest} 的 properties）：
 * 路由链的价值在于"确定性优先"，这条链路的正确性不该依赖某个模型今天心情如何。
 * 关掉之后，凡是需要模型才能出结论的场景在测试里都会落 UNKNOWN，
 * 于是"哪些路径根本没叫模型"就变成了可断言的事实——这正是本阶段最想验证的东西。
 * 带真实模型的链路另见 {@link com.jixiejia.agent.classify.M4ModelClassifyTest}。
 */
@SpringBootTest(properties = {
        "routing.model-layers-enabled=false",
        "routing.reference.enabled=false"
})
class M4RouterTest {

    private static final String TEST_CONVERSATION_PREFIX = "test-";

    @Autowired
    private MsgRouter msgRouter;

    @Autowired
    private KeywordWeightClassifier keywordClassifier;

    @Autowired
    private IntentClassifier intentClassifier;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private StickySessionStore stickySessionStore;

    @Autowired
    private ReferenceResolver referenceResolver;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AiAuditLogMapper auditLogMapper;

    @Autowired
    private AiConversationMapper conversationMapper;

    @Autowired
    private AiMessageMapper messageMapper;

    @Autowired
    private AiUserMapper aiUserMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 路由要求必须登录，所以每个用例都得有一个真实可用的账号 */
    private Long testUserId;

    @BeforeEach
    void createTestUser() {
        AiUser user = new AiUser();
        user.setUsername("test_router_" + UUID.randomUUID());
        user.setPassword("x");
        user.setStatus("0");
        user.setDelFlag("0");
        aiUserMapper.insert(user);
        testUserId = user.getId();
    }

    @AfterEach
    void cleanUp() {
        if (testUserId != null) {
            aiUserMapper.deleteById(testUserId);
            testUserId = null;
        }
        // 测试账号必须物理删除：MyBatis-Plus 的 deleteById 走的是 @TableLogic 逻辑删除，
        // 只把 del_flag 置成 '2'，行还留在表里，跑几十轮就是一屏垃圾数据。
        jdbcTemplate.update("DELETE FROM ai_user WHERE LEFT(username, 5) = 'test_'");

        // 测试往真实库里写了会话/消息/审计，按 test- 前缀清干净，别污染开发数据
        auditLogMapper.delete(Wrappers.<AiAuditLog>lambdaQuery()
                .likeRight(AiAuditLog::getConversationId, TEST_CONVERSATION_PREFIX));
        messageMapper.delete(Wrappers.<AiMessage>lambdaQuery()
                .likeRight(AiMessage::getConversationId, TEST_CONVERSATION_PREFIX));
        conversationMapper.delete(Wrappers.<AiConversation>lambdaQuery()
                .likeRight(AiConversation::getConversationId, TEST_CONVERSATION_PREFIX));
    }

    private static String newConversationId() {
        return TEST_CONVERSATION_PREFIX + UUID.randomUUID();
    }

    private RoutingRequest request(String conversationId, String message) {
        return new RoutingRequest(conversationId, testUserId, null, "USER", message);
    }

    // ---------------- 第 1 层：关键词加权 ----------------

    @Test
    @DisplayName("关键词高权重直出：不惊动模型")
    void keywordHighWeightShortCircuits() {
        IntentResult handoff = keywordClassifier.classify("我要转人工").orElseThrow();
        assertThat(handoff.intent()).isEqualTo(Intent.HANDOFF);
        assertThat(handoff.confidence()).isEqualTo(0.9);
        assertThat(handoff.layer()).isEqualTo(ClassifyLayer.KEYWORD);

        assertThat(keywordClassifier.classify("我想求租").orElseThrow().intent())
                .isEqualTo(Intent.QIUZU_QUERY);
        assertThat(keywordClassifier.classify("这个平台怎么投诉").orElseThrow().intent())
                .isEqualTo(Intent.COMPLAINT);
    }

    @Test
    @DisplayName("关键词歧义时不下结论，下沉给模型层")
    void ambiguousKeywordsFallThrough() {
        // "二手"指向设备意图，"出租"指向出租意图，两者都是高权重 —— 不能靠枚举顺序硬猜
        assertThat(keywordClassifier.classify("二手设备出租")).isEmpty();
        // 但倾向仍然要能取到，供模型层参考
        assertThat(keywordClassifier.hint("二手设备出租")).isPresent();
    }

    @Test
    @DisplayName("模型层关闭时，关键词覆盖不到的说法落 UNKNOWN 而不是报错")
    void unknownWhenModelLayersDisabled() {
        IntentResult result = intentClassifier.classify("帮我看看那个东西", null);
        assertThat(result.intent()).isEqualTo(Intent.UNKNOWN);
        assertThat(result.layer()).isEqualTo(ClassifyLayer.FALLBACK);
    }

    // ---------------- 第 1 步：登录门槛 ----------------

    @Test
    @DisplayName("身份：未登录一律拦下")
    void anonymousIsBlocked() {
        RoutingDecision blocked = msgRouter.route(
                new RoutingRequest(newConversationId(), null, null, "USER", "有没有二手的挖掘机"));

        assertThat(blocked.stage()).isEqualTo(RouteStage.IDENTITY);
        assertThat(blocked.isShortCircuited()).isTrue();
        assertThat(blocked.reply()).contains("登录");
        assertThat(blocked.agentKey()).isNull();
    }

    @Test
    @DisplayName("身份：账号不存在 / 已停用都拦下，正常账号放行")
    void identityCheck() {
        RoutingDecision missing = msgRouter.route(
                new RoutingRequest(newConversationId(), 99999999L, null, "USER", "你好"));
        assertThat(missing.stage()).isEqualTo(RouteStage.IDENTITY);
        assertThat(missing.reply()).contains("不存在");

        // 已登录的正常账号应当放行并正常路由
        RoutingDecision ok = msgRouter.route(request(newConversationId(), "有没有二手的挖掘机"));
        assertThat(ok.stage()).isNotEqualTo(RouteStage.IDENTITY);
        assertThat(ok.agentKey()).isEqualTo("EquipmentAgent");
    }

    @Test
    @DisplayName("身份：停用账号被拦下")
    void disabledAccountBlocked() {
        AiUser user = new AiUser();
        user.setUsername("test_disabled_" + UUID.randomUUID());
        user.setPassword("x");
        user.setStatus("1");
        user.setDelFlag("0");
        aiUserMapper.insert(user);

        try {
            RoutingDecision decision = msgRouter.route(
                    new RoutingRequest(newConversationId(), user.getId(), null, "USER", "你好"));
            assertThat(decision.stage()).isEqualTo(RouteStage.IDENTITY);
            assertThat(decision.reply()).contains("停用");
        } finally {
            aiUserMapper.deleteById(user.getId());
        }
    }

    // ---------------- 第 2 步：系统命令 ----------------

    @Test
    @DisplayName("/reset 清粘性并短路回复")
    void resetCommandClearsSticky() {
        String conversationId = newConversationId();

        RoutingDecision first = msgRouter.route(request(conversationId, "有没有二手的挖掘机"));
        assertThat(first.agentKey()).isEqualTo("EquipmentAgent");
        assertThat(stickySessionStore.find(conversationId)).isPresent();

        RoutingDecision reset = msgRouter.route(request(conversationId, "/reset"));
        assertThat(reset.stage()).isEqualTo(RouteStage.COMMAND);
        assertThat(reset.isShortCircuited()).isTrue();
        assertThat(reset.reply()).contains("已重置");

        assertThat(stickySessionStore.find(conversationId)).isEmpty();
    }

    @Test
    @DisplayName("已取消 /help：斜杠内容不再被当成命令，交回正常链路")
    void helpIsNoLongerACommand() {
        RoutingDecision decision = msgRouter.route(request(newConversationId(), "/help"));
        assertThat(decision.stage()).isNotEqualTo(RouteStage.COMMAND);
    }

    // ---------------- 第 3 步：粘性 + 关键词扫描 ----------------

    @Test
    @DisplayName("粘性：模糊追问靠粘性兜住，且不叫模型")
    void fuzzyFollowUpReusesStickyWithoutModel() {
        String conversationId = newConversationId();

        RoutingDecision first = msgRouter.route(request(conversationId, "有没有二手的挖掘机"));
        assertThat(first.agentKey()).isEqualTo("EquipmentAgent");
        assertThat(first.stage()).isEqualTo(RouteStage.EXECUTE);

        // "那这个呢"关键词命中不了 → 粘性兜住
        RoutingDecision followUp = msgRouter.route(request(conversationId, "那这个呢"));
        assertThat(followUp.stage()).isEqualTo(RouteStage.STICKY);
        assertThat(followUp.agentKey()).isEqualTo("EquipmentAgent");

        // 关键：这条路径没有经过任何模型层（classifyLayer 为空即为证据）
        assertThat(followUp.classifyLayer()).isNull();
        assertThat(followUp.reason()).contains("粘性");
    }

    @Test
    @DisplayName("粘性：不含关键词的**完整新问题**不该被粘性吃掉")
    void longNewQuestionIsNotSwallowedBySticky() {
        String conversationId = newConversationId();

        RoutingDecision first = msgRouter.route(request(conversationId, "有没有二手的挖掘机"));
        assertThat(first.agentKey()).isEqualTo("EquipmentAgent");

        // 实测踩过的场景：用户问完设备，接着问了一句安全规范。
        // 这句话**不含任何高权重关键词**（领域名词"挖掘机"刻意没进词表，
        // 因为它同时出现在出租/求租/需求里），但它是一句完整的新问题，不是追问。
        //
        // 原来的判据是"关键词没命中就用粘性兜住"，于是它被一直粘在 EquipmentAgent 上，
        // 连问两轮都答"我帮不上忙"——而知识库里其实有那份国标、答得上来。
        RoutingDecision second = msgRouter.route(request(conversationId,
                "挖掘机操纵杆和其他零件的距离应该控制在多少"));

        assertThat(second.stage())
                .as("完整的新问题必须重新分类，不能走粘性")
                .isNotEqualTo(RouteStage.STICKY);
        assertThat(second.agentKey())
                .as("不该再挂在上一轮的设备 Agent 上")
                .isNotEqualTo("EquipmentAgent");
    }

    @Test
    @DisplayName("粘性：**正好卡在字数阈值上**的完整问题也不能被吃掉")
    void shortButCompleteQuestionIsNotSwallowedBySticky() {
        String conversationId = newConversationId();

        // 用户实测报回来的场景：上一轮聊过租赁，接着问驾驶室要求，
        // 被粘在了 RentalAgent 上。根因是当时还有一条"字数 ≤ 12 就算追问"的判据，
        // 而这句话**正好 12 个字**——中文一句话信息密度高，12 个字已经是一句完整的问题。
        msgRouter.route(request(conversationId, "有没有二手的挖掘机出租"));

        RoutingDecision next = msgRouter.route(request(conversationId,
                "挖掘机驾驶室有什么要求吗"));

        assertThat(next.stage())
                .as("字数不该成为判据——它是在猜，中文里完整问题也可以很短")
                .isNotEqualTo(RouteStage.STICKY);
    }

    @Test
    @DisplayName("粘性：含指代词的追问仍然走粘性，不叫模型")
    void anaphoricFollowUpStillUsesSticky() {
        String conversationId = newConversationId();

        msgRouter.route(request(conversationId, "有没有二手的挖掘机"));

        // "那这个呢"含指代词——脱离上文根本理解不了，几乎必然是追问
        RoutingDecision followUp = msgRouter.route(request(conversationId, "那这个呢"));

        assertThat(followUp.stage()).isEqualTo(RouteStage.STICKY);
        assertThat(followUp.agentKey()).isEqualTo("EquipmentAgent");
        assertThat(followUp.classifyLayer()).as("这条路径不叫模型").isNull();
    }

    @Test
    @DisplayName("粘性：不含指代词的短句不再靠粘性兜底（「短」不是判据）")
    void shortNonAnaphoricQuestionDoesNotUseSticky() {
        String conversationId = newConversationId();

        msgRouter.route(request(conversationId, "有没有二手的挖掘机"));

        // "多少钱"只有三个字，但字面意思已经完整——它不指代任何东西。
        // 曾经它因为"够短"被粘性兜住；现在交给第 ④⑤ 步连同上文一起判，
        // 代价是多跑一次本地小模型（免费），换来的是**路由可预测**。
        RoutingDecision followUp = msgRouter.route(request(conversationId, "多少钱"));

        assertThat(followUp.stage())
                .as("判据只剩「含指代词」一条，短不再是理由")
                .isNotEqualTo(RouteStage.STICKY);
    }

    @Test
    @DisplayName("粘性：关键词命中同一话题时继续沿用，也不叫模型")
    void sameTopicKeywordKeepsSticky() {
        String conversationId = newConversationId();

        msgRouter.route(request(conversationId, "有没有二手的挖掘机"));

        RoutingDecision second = msgRouter.route(request(conversationId, "有二手装载机吗"));
        assertThat(second.stage()).isEqualTo(RouteStage.STICKY);
        assertThat(second.agentKey()).isEqualTo("EquipmentAgent");
        // 关键词层已经定案，同样没走模型
        assertThat(second.classifyLayer()).isEqualTo(ClassifyLayer.KEYWORD);
        assertThat(second.reason()).contains("与会话粘性一致");
    }

    @Test
    @DisplayName("粘性：关键词命中不同话题时立刻切换 Agent")
    void differentTopicSwitchesAgent() {
        String conversationId = newConversationId();

        msgRouter.route(request(conversationId, "有没有二手的挖掘机"));

        // 关键词高置信指向别的领域 → 切走，不被粘性拽住
        RoutingDecision switched = msgRouter.route(request(conversationId, "我想求租"));
        assertThat(switched.stage()).isEqualTo(RouteStage.EXECUTE);
        assertThat(switched.agentKey()).isEqualTo("RentalAgent");
        assertThat(switched.reason()).contains("切换到");
    }

    @Test
    @DisplayName("粘性：没有粘性且关键词未命中时，才真正走模型")
    void fallsBackToModelWhenNothingHelps() {
        String conversationId = newConversationId();

        // 全新会话 + 关键词命中不到 → 必须走分类（测试里模型层关着，故落 UNKNOWN → 兜底 Agent）
        RoutingDecision decision = msgRouter.route(request(conversationId, "帮我看看那个东西"));
        assertThat(decision.agentKey()).isEqualTo("GeneralAgent");
        assertThat(decision.intent()).isEqualTo(Intent.UNKNOWN);
    }

    // ---------------- 第 6 步：Agent 匹配 ----------------

    @Test
    @DisplayName("意图 → Agent：按能力交集匹配，兜底交给 GeneralAgent")
    void intentMapsToAgent() {
        assertThat(agentRegistry.match(Intent.EQUIPMENT_QUERY, "USER").orElseThrow().agentKey())
                .isEqualTo("EquipmentAgent");
        assertThat(agentRegistry.match(Intent.CHUZU_QUERY, "USER").orElseThrow().agentKey())
                .isEqualTo("RentalAgent");
        assertThat(agentRegistry.match(Intent.QIUZU_QUERY, "USER").orElseThrow().agentKey())
                .isEqualTo("RentalAgent");
        assertThat(agentRegistry.match(Intent.NEWS_QUERY, "USER").orElseThrow().agentKey())
                .isEqualTo("KnowledgeAgent");
        assertThat(agentRegistry.match(Intent.PUBLISH_CHUZU, "USER").orElseThrow().agentKey())
                .isEqualTo("PublishAgent");

        AgentRegistry.AgentMatch fallback = agentRegistry.match(Intent.UNKNOWN, "USER").orElseThrow();
        assertThat(fallback.agentKey()).isEqualTo("GeneralAgent");
        assertThat(fallback.fallback()).isTrue();
    }

    @Test
    @DisplayName("Agent 只能拿到自己能力范围内的工具")
    void agentToolsAreScopedByCapability() {
        AgentRegistry.AgentMatch equipment = agentRegistry.match(Intent.EQUIPMENT_QUERY, "USER").orElseThrow();
        assertThat(equipment.toolNames())
                .containsExactlyInAnyOrder("search_equipment", "get_equipment_detail", "search_xunjia");

        AgentRegistry.AgentMatch rental = agentRegistry.match(Intent.CHUZU_QUERY, "USER").orElseThrow();
        // "用机需求"已经并进求租（同一个业务概念），不再是独立工具；
        // "新机询价"是"想买新机"，归到设备买卖那边了
        assertThat(rental.toolNames())
                .containsExactlyInAnyOrder("search_chuzu", "search_qiuzu");

        assertThat(agentRegistry.fallback("USER").orElseThrow().toolNames()).isEmpty();
    }

    @Test
    @DisplayName("角色白名单与 Agent 能力取交集")
    void rolePermissionIntersectsCapability() {
        Set<String> adminTools = toolRegistry.toolNamesForRole("ADMIN");
        Set<String> userTools = toolRegistry.toolNamesForRole("USER");

        AgentRegistry.AgentMatch forAdmin = agentRegistry.match(Intent.EQUIPMENT_QUERY, "ADMIN").orElseThrow();
        AgentRegistry.AgentMatch forUser = agentRegistry.match(Intent.EQUIPMENT_QUERY, "USER").orElseThrow();

        assertThat(adminTools).containsAll(forAdmin.toolNames());
        assertThat(userTools).containsAll(forUser.toolNames());

        assertThat(agentRegistry.match(Intent.EQUIPMENT_QUERY, "NO_SUCH_ROLE")
                .orElseThrow().toolNames()).isEmpty();
    }

    // ---------------- 短路：转人工 / 投诉 ----------------

    @Test
    @DisplayName("转人工与投诉都短路，不进 Agent")
    void shortCircuitIntents() {
        RoutingDecision handoff = msgRouter.route(request(newConversationId(), "转人工"));
        assertThat(handoff.stage()).isEqualTo(RouteStage.HANDOFF);
        assertThat(handoff.agentKey()).isNull();
        assertThat(handoff.reply()).contains("转接人工客服");

        RoutingDecision complaint = msgRouter.route(request(newConversationId(), "我要投诉"));
        assertThat(complaint.stage()).isEqualTo(RouteStage.COMPLAINT);
        assertThat(complaint.agentKey()).isNull();
        // 投诉只给选项，不自动建单
        assertThat(complaint.reply()).contains("转人工");
    }

    // ---------------- 第 7 步：审计 ----------------

    @Test
    @DisplayName("指代消解只认真正的指代词，追问词归粘性管")
    void onlyRealAnaphoraTriggerResolution() {
        // 这些字面说不清指谁，必须回指上文
        assertThat(referenceResolver.needsResolution("这个多少钱")).isTrue();
        assertThat(referenceResolver.needsResolution("那台还在吗")).isTrue();
        assertThat(referenceResolver.needsResolution("它多少钱")).isTrue();

        // 这些**不含**指代词，字面意思已完整——它们不再由粘性兜底，
        // 而是走第 ④⑤ 步连同上文一起判（多一次本地小模型，免费）。
        // 曾经它们靠"够短"进粘性快路径，那条判据已经被删掉了。
        assertThat(referenceResolver.needsResolution("还有吗")).isFalse();
        assertThat(referenceResolver.needsResolution("有没有便宜点的")).isFalse();
        assertThat(referenceResolver.needsResolution("换一个看看")).isFalse();
        assertThat(referenceResolver.needsResolution("别的呢")).isFalse();
    }

    @Test
    @DisplayName("粘性：同一个 Agent 的模糊追问仍复用，但粘性 Agent 下线时退回重新匹配")
    void stickyFallsBackWhenAgentGone() {
        String conversationId = newConversationId();

        // 手工塞一条指向不存在 Agent 的粘性记录，模拟 Agent 被停用/删除后的残留
        stickySessionStore.save(conversationId, "NoSuchAgent", Intent.EQUIPMENT_QUERY);

        RoutingDecision decision = msgRouter.route(request(conversationId, "那这个呢"));
        // 不能把消息硬塞给一个已下线的 Agent，必须重新走到匹配逻辑
        assertThat(decision.agentKey()).isNotNull().isNotEqualTo("NoSuchAgent");
    }

    @Test
    @DisplayName("审计：每次路由都落一条，含命中的步骤与分类层")
    void auditIsWritten() {
        String conversationId = newConversationId();
        msgRouter.route(request(conversationId, "有没有二手的挖掘机"));

        var rows = auditLogMapper.selectList(Wrappers.<AiAuditLog>lambdaQuery()
                .eq(AiAuditLog::getConversationId, conversationId));

        assertThat(rows).hasSize(1);
        AiAuditLog row = rows.get(0);
        assertThat(row.getRouteStage()).isEqualTo(RouteStage.EXECUTE.code());
        assertThat(row.getIntent()).isEqualTo(Intent.EQUIPMENT_QUERY.name());
        assertThat(row.getClassifyLayer()).isEqualTo(ClassifyLayer.KEYWORD.code());
        assertThat(row.getAgentKey()).isEqualTo("EquipmentAgent");
        assertThat(row.getToolNames()).contains("search_equipment");
        assertThat(row.getSuccess()).isEqualTo(1);
        assertThat(row.getLatencyMs()).isNotNull();
    }

    @Test
    @DisplayName("审计：短路路径也留痕")
    void auditRecordsShortCircuit() {
        String conversationId = newConversationId();
        msgRouter.route(request(conversationId, "我要投诉"));

        var rows = auditLogMapper.selectList(Wrappers.<AiAuditLog>lambdaQuery()
                .eq(AiAuditLog::getConversationId, conversationId));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRouteStage()).isEqualTo(RouteStage.COMPLAINT.code());
        assertThat(rows.get(0).getAgentKey()).isNull();
    }

    @Test
    @DisplayName("审计：未登录也被记录，便于排查")
    void auditRecordsIdentityBlock() {
        String conversationId = newConversationId();
        msgRouter.route(new RoutingRequest(conversationId, null, null, "USER", "你好"));

        var rows = auditLogMapper.selectList(Wrappers.<AiAuditLog>lambdaQuery()
                .eq(AiAuditLog::getConversationId, conversationId));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRouteStage()).isEqualTo(RouteStage.IDENTITY.code());
        assertThat(rows.get(0).getUserId()).isNull();
    }
}
