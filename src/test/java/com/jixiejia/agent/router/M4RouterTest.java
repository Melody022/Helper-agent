package com.jixiejia.agent.router;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.classify.ClassifyLayer;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.classify.IntentClassifier;
import com.jixiejia.agent.classify.IntentResult;
import com.jixiejia.agent.classify.KeywordWeightClassifier;
import com.jixiejia.agent.persistence.entity.ai.AiAuditLog;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiAuditLogMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import com.jixiejia.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 路由链测试。
 *
 * <p>刻意关掉后两层模型与指代消解（见 {@code @SpringBootTest} 的 properties）：
 * 路由链的价值在于"确定性优先"，这条链路的正确性不该依赖某个模型今天心情如何。
 * 带模型的完整链路另见 {@link M4ModelClassifyTest}。
 */
@SpringBootTest(properties = {
        "routing.model-layers-enabled=false",
        "routing.reference.enabled=false"
})
class M4RouterTest {

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
    private ToolRegistry toolRegistry;

    @Autowired
    private AiAuditLogMapper auditLogMapper;

    @Autowired
    private AiUserMapper aiUserMapper;

    private static String newConversationId() {
        return "test-" + UUID.randomUUID();
    }

    private RoutingRequest request(String conversationId, String message) {
        return new RoutingRequest(conversationId, null, null, "USER", message);
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
        // "二手设备"指向设备意图，"出租"指向出租意图，两者都是高权重 —— 不能靠枚举顺序硬猜
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
        assertThat(agentRegistry.match(Intent.DEMAND_QUERY, "USER").orElseThrow().agentKey())
                .isEqualTo("RentalAgent");
        assertThat(agentRegistry.match(Intent.NEWS_QUERY, "USER").orElseThrow().agentKey())
                .isEqualTo("KnowledgeAgent");
        assertThat(agentRegistry.match(Intent.PUBLISH_CHUZU, "USER").orElseThrow().agentKey())
                .isEqualTo("PublishAgent");

        // UNKNOWN 没有任何能力需求，匹配不到具体 Agent，落到兜底
        AgentRegistry.AgentMatch fallback = agentRegistry.match(Intent.UNKNOWN, "USER").orElseThrow();
        assertThat(fallback.agentKey()).isEqualTo("GeneralAgent");
        assertThat(fallback.fallback()).isTrue();
    }

    @Test
    @DisplayName("Agent 只能拿到自己能力范围内的工具")
    void agentToolsAreScopedByCapability() {
        // EquipmentAgent 声明的能力只有 equipment，不该拿到出租/资讯工具
        AgentRegistry.AgentMatch equipment = agentRegistry.match(Intent.EQUIPMENT_QUERY, "USER").orElseThrow();
        assertThat(equipment.toolNames())
                .containsExactlyInAnyOrder("search_equipment", "get_equipment_detail");

        AgentRegistry.AgentMatch rental = agentRegistry.match(Intent.CHUZU_QUERY, "USER").orElseThrow();
        assertThat(rental.toolNames())
                .containsExactlyInAnyOrder("search_chuzu", "search_qiuzu", "search_demand", "search_xunjia");

        // 兜底 Agent 不声明能力，因此一个工具都不该有
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

        // 不存在的角色拿不到任何工具
        assertThat(agentRegistry.match(Intent.EQUIPMENT_QUERY, "NO_SUCH_ROLE")
                .orElseThrow().toolNames()).isEmpty();
    }

    // ---------------- 第 2 步：系统命令 ----------------

    @Test
    @DisplayName("/reset 清粘性并短路回复")
    void resetCommandClearsSticky() {
        String conversationId = newConversationId();

        // 先建立粘性
        RoutingDecision first = msgRouter.route(request(conversationId, "有没有二手的挖掘机"));
        assertThat(first.agentKey()).isEqualTo("EquipmentAgent");
        assertThat(stickySessionStore.find(conversationId)).isPresent();

        // /reset 命中命令并短路
        RoutingDecision reset = msgRouter.route(request(conversationId, "/reset"));
        assertThat(reset.stage()).isEqualTo(RouteStage.COMMAND);
        assertThat(reset.isShortCircuited()).isTrue();
        assertThat(reset.reply()).contains("已重置");

        // 粘性被清掉
        assertThat(stickySessionStore.find(conversationId)).isEmpty();
    }

    @Test
    @DisplayName("/help 给出能力清单")
    void helpCommand() {
        RoutingDecision decision = msgRouter.route(request(newConversationId(), "/help"));
        assertThat(decision.stage()).isEqualTo(RouteStage.COMMAND);
        assertThat(decision.reply()).contains("找设备");
    }

    // ---------------- 第 1 步：身份 ----------------

    @Test
    @DisplayName("身份：不存在的账号被拦下，匿名访问放行")
    void identityCheck() {
        RoutingDecision blocked = msgRouter.route(
                new RoutingRequest(newConversationId(), 99999999L, null, "USER", "你好"));
        assertThat(blocked.stage()).isEqualTo(RouteStage.IDENTITY);
        assertThat(blocked.isShortCircuited()).isTrue();

        // 匿名（aiUserId 为 null）是客服场景的正常情况，应当放行
        RoutingDecision anonymous = msgRouter.route(request(newConversationId(), "有没有二手的挖掘机"));
        assertThat(anonymous.stage()).isNotEqualTo(RouteStage.IDENTITY);
        assertThat(anonymous.agentKey()).isEqualTo("EquipmentAgent");
    }

    @Test
    @DisplayName("身份：停用账号被拦下")
    void disabledAccountBlocked() {
        String username = "test_disabled_" + UUID.randomUUID();
        AiUser user = new AiUser();
        user.setUsername(username);
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

    // ---------------- 第 3 步：会话粘性 ----------------

    @Test
    @DisplayName("粘性：分类没把握时沿用上一轮 Agent，高置信换领域时切换")
    void stickySessionReuseAndSwitch() {
        String conversationId = newConversationId();

        RoutingDecision first = msgRouter.route(request(conversationId, "有没有二手的挖掘机"));
        assertThat(first.agentKey()).isEqualTo("EquipmentAgent");
        assertThat(first.stage()).isEqualTo(RouteStage.EXECUTE);

        // 关键词命中不了的追问 → UNKNOWN → 沿用上一轮的 EquipmentAgent
        RoutingDecision followUp = msgRouter.route(request(conversationId, "那这个呢"));
        assertThat(followUp.stage()).isEqualTo(RouteStage.STICKY);
        assertThat(followUp.agentKey()).isEqualTo("EquipmentAgent");

        // 高置信指向别的领域 → 切换
        RoutingDecision switched = msgRouter.route(request(conversationId, "我想求租"));
        assertThat(switched.stage()).isEqualTo(RouteStage.EXECUTE);
        assertThat(switched.agentKey()).isEqualTo("RentalAgent");
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
}
