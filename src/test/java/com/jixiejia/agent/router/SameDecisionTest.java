package com.jixiejia.agent.router;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.persistence.entity.ai.AiAuditLog;
import com.jixiejia.agent.persistence.entity.ai.AiConversation;
import com.jixiejia.agent.persistence.entity.ai.AiMessage;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiAuditLogMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiConversationMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiMessageMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由链的<b>等价性对照测试</b>：同一批输入，状态图与原来的方法链必须产出<b>逐字段相同</b>的
 * {@link RoutingDecision}。
 *
 * <p><b>为什么要有这个。</b>把一条跑在生产链路上的 7 步路由改写成图，最大的风险不是"图写不出来"，
 * 而是<b>"改错了看不出来"</b>——{@code M4RouterTest} 那 25 个用例断言的是行为，
 * 但它同时依赖 {@code MsgRouter} 这个入口；一旦把测试跟着代码一起改，
 * 就分不清一个红灯到底是"图写错了"还是"测试本就该跟着改"。
 *
 * <p>所以这里让<b>两套实现并存</b>，用旧实现当标准答案：旧方法链跑一遍、图跑一遍，
 * 逐字段比对。等它稳定通过了，再切换调用方、删掉旧路径。
 *
 * <p><b>为什么每次要用两个不同的会话 ID。</b>路由是有副作用的——收尾会把命中的 Agent 写进
 * Redis 粘性。如果两次调用共用同一个会话 ID，第二次就会读到第一次写下的粘性，
 * 于是"两次结果不同"变成必然，测的就不是等价性而是执行顺序了。
 * 需要粘性的场景由 {@link #seedSticky} 显式地给两个会话各写一份。
 *
 * <p>模型层与指代消解都关掉（同 {@code M4RouterTest}），让整条链落在纯规则路径上——
 * 这样出现差异时一定是代码问题，不是模型今天心情不好。
 */
@SpringBootTest(properties = {
        "routing.model-layers-enabled=false",
        "routing.reference.enabled=false"
})
class SameDecisionTest {

    private static final String TEST_CONVERSATION_PREFIX = "test-";

    @Autowired
    private MsgRouter msgRouter;

    @Autowired
    private RoutingGraph routingGraph;

    @Autowired
    private StickySessionStore stickySessionStore;

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

    private Long testUserId;

    /** 停用账号用例用，单独建、单独删 */
    private Long disabledUserId;

    @BeforeEach
    void createTestUser() {
        testUserId = insertUser("0");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_user WHERE LEFT(username, 5) = 'test_'");
        auditLogMapper.delete(Wrappers.<AiAuditLog>lambdaQuery()
                .likeRight(AiAuditLog::getConversationId, TEST_CONVERSATION_PREFIX));
        messageMapper.delete(Wrappers.<AiMessage>lambdaQuery()
                .likeRight(AiMessage::getConversationId, TEST_CONVERSATION_PREFIX));
        conversationMapper.delete(Wrappers.<AiConversation>lambdaQuery()
                .likeRight(AiConversation::getConversationId, TEST_CONVERSATION_PREFIX));
    }

    private Long insertUser(String status) {
        AiUser user = new AiUser();
        user.setUsername("test_same_" + UUID.randomUUID());
        user.setPassword("x");
        user.setStatus(status);
        user.setDelFlag("0");
        aiUserMapper.insert(user);
        return user.getId();
    }

    // ---------------- 对照骨架 ----------------

    /**
     * 同一句话，旧链和新图各跑一遍，逐字段比对。
     *
     * @param message            用户输入
     * @param stickyAgentKey     预置的粘性 Agent（null 表示不预置）
     * @param stickyIntent       预置的粘性意图
     */
    private void assertSame(String message, String stickyAgentKey, Intent stickyIntent) {
        String convA = TEST_CONVERSATION_PREFIX + UUID.randomUUID();
        String convB = TEST_CONVERSATION_PREFIX + UUID.randomUUID();
        seedSticky(convA, stickyAgentKey, stickyIntent);
        seedSticky(convB, stickyAgentKey, stickyIntent);

        RoutingDecision viaChain = msgRouter.route(
                new RoutingRequest(convA, testUserId, null, "USER", message));
        RoutingDecision viaGraph = routingGraph.route(
                new RoutingRequest(convB, testUserId, null, "USER", message));

        assertSameDecision(viaChain, viaGraph, message);
    }

    private void assertSame(String message) {
        assertSame(message, null, null);
    }

    /** 未登录/账号不存在/停用这类要换 userId 的场景。 */
    private void assertSameForUser(Long userId, String message) {
        String convA = TEST_CONVERSATION_PREFIX + UUID.randomUUID();
        String convB = TEST_CONVERSATION_PREFIX + UUID.randomUUID();

        RoutingDecision viaChain = msgRouter.route(
                new RoutingRequest(convA, userId, null, "USER", message));
        RoutingDecision viaGraph = routingGraph.route(
                new RoutingRequest(convB, userId, null, "USER", message));

        assertSameDecision(viaChain, viaGraph, message);
    }

    private void seedSticky(String conversationId, String agentKey, Intent intent) {
        if (agentKey != null) {
            stickySessionStore.save(conversationId, agentKey, intent);
        }
    }

    /**
     * 逐字段比对。
     *
     * <p>字段清单就是 {@link RoutingDecision} 的全部 10 个字段加上 3 个派生判断——
     * 之所以连派生判断也断言，是因为它们被下游（{@code ChatService}、测试）直接使用，
     * 光比较原始字段无法保证"调用方看到的行为"一致。
     */
    private static void assertSameDecision(RoutingDecision chain, RoutingDecision graph, String message) {
        String ctx = "@「" + message + "」";
        assertThat(graph.stage()).as("stage " + ctx).isEqualTo(chain.stage());
        assertThat(graph.intent()).as("intent " + ctx).isEqualTo(chain.intent());
        assertThat(graph.confidence()).as("confidence " + ctx).isEqualTo(chain.confidence());
        assertThat(graph.classifyLayer()).as("classifyLayer " + ctx).isEqualTo(chain.classifyLayer());
        assertThat(graph.agentKey()).as("agentKey " + ctx).isEqualTo(chain.agentKey());
        assertThat(graph.agentKeys()).as("agentKeys " + ctx).isEqualTo(chain.agentKeys());
        assertThat(graph.toolNames()).as("toolNames " + ctx).isEqualTo(chain.toolNames());
        assertThat(graph.resolvedText()).as("resolvedText " + ctx).isEqualTo(chain.resolvedText());
        assertThat(graph.reply()).as("reply " + ctx).isEqualTo(chain.reply());
        assertThat(graph.reason()).as("reason " + ctx).isEqualTo(chain.reason());

        assertThat(graph.isShortCircuited()).as("isShortCircuited " + ctx).isEqualTo(chain.isShortCircuited());
        assertThat(graph.isComposite()).as("isComposite " + ctx).isEqualTo(chain.isComposite());
        assertThat(graph.targetAgents()).as("targetAgents " + ctx).isEqualTo(chain.targetAgents());
    }

    // ---------------- 0. 图本身 ----------------

    @Test
    @DisplayName("路由状态图能构建起来（构图失败会被 @PostConstruct 吞掉，必须显式验）")
    void graphIsBuilt() {
        assertThat(routingGraph.available())
                .as("路由状态图应当在启动时构建成功")
                .isTrue();
    }

    // ---------------- ① 身份 ----------------

    @Test
    @DisplayName("等价 · 身份：未登录")
    void anonymous() {
        assertSameForUser(null, "有没有二手的挖掘机");
    }

    @Test
    @DisplayName("等价 · 身份：账号不存在")
    void userNotFound() {
        assertSameForUser(999_999_999L, "有没有二手的挖掘机");
    }

    @Test
    @DisplayName("等价 · 身份：账号已停用")
    void userDisabled() {
        disabledUserId = insertUser("1");
        assertSameForUser(disabledUserId, "有没有二手的挖掘机");
    }

    // ---------------- ② 系统命令 ----------------

    @Test
    @DisplayName("等价 · 命令：/reset 清粘性并短路")
    void resetCommand() {
        // 粘性预置上，才能验证 /reset 确实清了它——顺便比对"清完之后"的决策
        assertSame("/reset", "RentalAgent", Intent.CHUZU_QUERY);
    }

    @Test
    @DisplayName("等价 · 命令：/help 已取消，交回正常链路")
    void helpIsNotACommand() {
        assertSame("/help");
    }

    // ---------------- ③ 粘性 / 关键词 / 跨域 ----------------

    @Test
    @DisplayName("等价 · 关键词：转人工短路")
    void handoffKeyword() {
        assertSame("我要转人工");
    }

    @Test
    @DisplayName("等价 · 关键词：投诉短路")
    void complaintKeyword() {
        assertSame("我要投诉");
    }

    @Test
    @DisplayName("等价 · 跨域：两个不同领域的高权重词 → 综合子图")
    void crossDomain() {
        assertSame("有二手的挖掘机吗，另外有没有挖掘机出租");
    }

    @Test
    @DisplayName("等价 · 跨域：同一个 Agent 覆盖的两个意图不算跨域")
    void sameAgentIsNotCrossDomain() {
        assertSame("有出租的机器吗，也有求租的需求吗");
    }

    @Test
    @DisplayName("等价 · 单域：只命中一个领域时保持原路径")
    void singleDomain() {
        assertSame("附近有挖掘机出租吗");
        assertSame("有没有二手的挖掘机");
    }

    @Test
    @DisplayName("等价 · 粘性：关键词命中同一话题时继续沿用")
    void sameTopicKeepsSticky() {
        assertSame("附近有挖掘机出租吗", "RentalAgent", Intent.CHUZU_QUERY);
    }

    @Test
    @DisplayName("等价 · 粘性：关键词命中不同话题时切换 Agent")
    void differentTopicSwitchesAgent() {
        assertSame("有没有二手的挖掘机", "RentalAgent", Intent.CHUZU_QUERY);
    }

    @Test
    @DisplayName("等价 · 粘性：含指代词的追问沿用上一轮 Agent")
    void anaphoricFollowUpUsesSticky() {
        assertSame("那这个呢", "RentalAgent", Intent.CHUZU_QUERY);
    }

    @Test
    @DisplayName("等价 · 粘性：粘性里的 Agent 已下线时退回重新匹配")
    void stickyAgentGone() {
        assertSame("那这个呢", "NoSuchAgent", Intent.CHUZU_QUERY);
    }

    // ---------------- ④⑤⑥ 指代消解 → 意图 → 匹配 ----------------

    @Test
    @DisplayName("等价 · 兜底：没有任何线索时落 UNKNOWN（模型层关着）")
    void fallsThroughToUnknown() {
        assertSame("随便说点什么吧");
    }

    @Test
    @DisplayName("等价 · 兜底：不含指代词的短句不被粘性吃掉")
    void shortNonAnaphoric() {
        assertSame("多少钱", "RentalAgent", Intent.CHUZU_QUERY);
    }
}
