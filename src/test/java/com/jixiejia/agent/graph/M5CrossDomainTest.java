package com.jixiejia.agent.graph;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.chat.ChatService;
import com.jixiejia.agent.classify.Intent;
import com.jixiejia.agent.persistence.entity.ai.AiAuditLog;
import com.jixiejia.agent.persistence.entity.ai.AiConversation;
import com.jixiejia.agent.persistence.entity.ai.AiMessage;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiAuditLogMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiConversationMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiMessageMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import com.jixiejia.agent.router.MsgRouter;
import com.jixiejia.agent.router.RouteStage;
import com.jixiejia.agent.router.RoutingDecision;
import com.jixiejia.agent.router.RoutingRequest;
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
 * 跨域综合测试。
 *
 * <p>分两层：判定层是纯规则的，可以精确断言；执行层要真跑几个 Agent，
 * 所以只做宽松断言（答出真实数据即可），并把关键事实打印出来供人工核对。
 */
@SpringBootTest
class M5CrossDomainTest {

    private static final String CONV_PREFIX = "test-";

    @Autowired
    private MsgRouter msgRouter;

    @Autowired
    private ChatService chatService;

    @Autowired
    private CompositeGraph compositeGraph;

    @Autowired
    private AiUserMapper userMapper;

    @Autowired
    private AiAuditLogMapper auditLogMapper;

    @Autowired
    private AiMessageMapper messageMapper;

    @Autowired
    private AiConversationMapper conversationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void createUser() {
        AiUser user = new AiUser();
        user.setUsername("test_cross_" + UUID.randomUUID());
        user.setPassword("x");
        user.setStatus("0");
        user.setDelFlag("0");
        userMapper.insert(user);
        userId = user.getId();
    }

    @AfterEach
    void cleanUp() {
        auditLogMapper.delete(Wrappers.<AiAuditLog>lambdaQuery()
                .likeRight(AiAuditLog::getConversationId, CONV_PREFIX));
        messageMapper.delete(Wrappers.<AiMessage>lambdaQuery()
                .likeRight(AiMessage::getConversationId, CONV_PREFIX));
        conversationMapper.delete(Wrappers.<AiConversation>lambdaQuery()
                .likeRight(AiConversation::getConversationId, CONV_PREFIX));
        jdbcTemplate.update("DELETE FROM ai_user WHERE LEFT(username, 5) = 'test_'");
    }

    private RoutingDecision route(String message) {
        return msgRouter.route(new RoutingRequest(
                CONV_PREFIX + UUID.randomUUID(), userId, null, "USER", message));
    }

    // ---------------- 判定层（纯规则，可精确断言） ----------------

    @Test
    @DisplayName("领域 → 意图反查：跨域时模型给的是能力标识，必须能还原成意图")
    void capabilityResolvesBackToIntent() {
        assertThat(Intent.byCapability("equipment")).contains(Intent.EQUIPMENT_QUERY);
        assertThat(Intent.byCapability("chuzu")).contains(Intent.CHUZU_QUERY);
        assertThat(Intent.byCapability("policy")).contains(Intent.POLICY_QUERY);

        // 关键：CROSS_DOMAIN 自己也声明了这 5 个能力，但它是判定结果不是候选，
        // 反查必须优先命中具体的查询意图，否则又会绕回"跨域匹配跨域"
        assertThat(Intent.byCapability("equipment")).get().isNotEqualTo(Intent.CROSS_DOMAIN);

        assertThat(Intent.byCapability("不存在的领域")).isEmpty();
        assertThat(Intent.byCapability(null)).isEmpty();
    }

    @Test
    @DisplayName("跨域子图能构建起来（构图失败会被 @PostConstruct 吞掉，必须显式验）")
    void graphIsBuilt() {
        assertThat(compositeGraph.available())
                .as("跨域子图应当在启动时构建成功")
                .isTrue();
        assertThat(compositeGraph.maxAgents()).isPositive();
    }

    @Test
    @DisplayName("跨域：两个不同领域的高权重词命中 → 走综合子图")
    void twoDomainsTriggerComposite() {
        RoutingDecision decision = route("有二手的挖掘机吗，另外有没有挖掘机出租");

        assertThat(decision.stage()).isEqualTo(RouteStage.COMPOSITE);
        assertThat(decision.isComposite()).isTrue();
        assertThat(decision.targetAgents())
                .containsExactlyInAnyOrder("EquipmentAgent", "RentalAgent");
        // 跨域时不该再落到单个 agentKey，否则又会被单 Agent 逻辑处理
        assertThat(decision.agentKey()).isNull();
        assertThat(decision.reason()).contains("跨域综合");
    }

    @Test
    @DisplayName("单域不触发：只命中一个领域时保持原路径")
    void singleDomainDoesNotTrigger() {
        assertThat(route("附近有挖掘机出租吗").stage()).isNotEqualTo(RouteStage.COMPOSITE);
        assertThat(route("有没有二手的挖掘机").stage()).isNotEqualTo(RouteStage.COMPOSITE);
        assertThat(route("我想求租").stage()).isNotEqualTo(RouteStage.COMPOSITE);
    }

    @Test
    @DisplayName("按 Agent 去重：两个意图若属同一个 Agent，不触发跨域")
    void sameAgentIsNotCrossDomain() {
        // "出租"(CHUZU) 和 "求租"(QIUZU) 都是高权重，但两者都由 RentalAgent 负责。
        // 若按意图数判定，这里会被误判成跨域，然后把同一个 Agent 跑两遍——纯浪费。
        RoutingDecision decision = route("有出租的机器吗，也有求租的需求吗");

        assertThat(decision.isComposite())
                .as("同一个 Agent 覆盖的多个意图不该触发跨域")
                .isFalse();
        assertThat(decision.stage()).isNotEqualTo(RouteStage.COMPOSITE);

        // 注意这里**不能**断言最终落到 RentalAgent：所有高权重词都是 0.9 分，
        // 两个高权重意图必然同分，SingleIntent 的"必须严格高于第二名"判据会让它
        // 判不出来、下沉给模型层。这正是跨域判定必须排在单意图定案**之前**的原因——
        // 单意图那条路对"一次命中多个域"的输入天然是判不出来的。
    }

    @Test
    @DisplayName("跨域也被记进审计，便于排查这条昂贵路径")
    void compositeIsAudited() {
        String conversationId = CONV_PREFIX + UUID.randomUUID();
        msgRouter.route(new RoutingRequest(conversationId, userId, null, "USER",
                "有二手的挖掘机吗，另外有没有挖掘机出租"));

        var rows = auditLogMapper.selectList(Wrappers.<AiAuditLog>lambdaQuery()
                .eq(AiAuditLog::getConversationId, conversationId));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRouteStage()).isEqualTo(RouteStage.COMPOSITE.code());
        assertThat(rows.get(0).getIntent()).isEqualTo("CROSS_DOMAIN");
    }

    // ---------------- 执行层（真跑，宽松断言） ----------------

    @Test
    @DisplayName("真跑：跨域问题同时答出设备与出租两类真实信息")
    void compositeAnswersBothDomains() {
        ChatService.ChatResult result = chatService.chat(userId, null, "USER", null,
                "有二手的挖掘机吗，另外有没有挖掘机出租");

        System.out.println("[跨域回答]\n" + result.answer());

        assertThat(result.agentKey()).contains("EquipmentAgent").contains("RentalAgent");
        assertThat(result.answer()).isNotBlank();
        // 兜底话术意味着整条链路失败了
        assertThat(result.answer()).doesNotContain("都没能查到");
        // 真实库里两边都有挖掘机数据
        assertThat(result.answer()).contains("挖掘机");
        assertThat(result.answer()).as("应当同时包含出租相关信息")
                .containsAnyOf("出租", "租金", "待租");
    }
}
