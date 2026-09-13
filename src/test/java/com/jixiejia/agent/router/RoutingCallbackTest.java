package com.jixiejia.agent.router;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiAuditLog;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiAuditLogMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 回调的触发时机。
 *
 * <p><b>为什么单独测这个。</b>执行搬进图之后，"路由完成就推给前端"这个回调
 * 从图外面的一个方法调用，变成了图内部 {@code dispatch} 节点通过
 * {@code RunnableConfig} 的 metadata 取出来执行——**这条路没有任何别的测试覆盖**。
 * 一旦 metadata 没传进去，前端就再也收不到 route 事件，
 * 表现为"要等整段回答生成完才看到反馈"，是个很隐蔽的体验回归。
 *
 * <p>顺带把时机钉住：回调必须发生在<b>审计落库之后、执行开始之前</b>。
 * 早了前端拿不到审计过的内容，晚了就失去"最耗时那步之前先反馈"的意义。
 */
@SpringBootTest(properties = {
        "routing.model-layers-enabled=false",
        "routing.reference.enabled=false"
})
class RoutingCallbackTest {

    private static final String TEST_CONVERSATION_PREFIX = "test-cb-";

    @Autowired
    private RoutingGraph routingGraph;

    @Autowired
    private AiUserMapper aiUserMapper;

    @Autowired
    private AiAuditLogMapper auditLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void createTestUser() {
        AiUser user = new AiUser();
        user.setUsername("test_cb_" + UUID.randomUUID());
        user.setPassword("x");
        user.setStatus("0");
        user.setDelFlag("0");
        aiUserMapper.insert(user);
        userId = user.getId();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_user WHERE LEFT(username, 5) = 'test_'");
        auditLogMapper.delete(Wrappers.<AiAuditLog>lambdaQuery()
                .likeRight(AiAuditLog::getConversationId, TEST_CONVERSATION_PREFIX));
    }

    @Test
    @DisplayName("回调会被触发，且发生在审计落库之后")
    void callbackFiresAfterAudit() {
        String convId = TEST_CONVERSATION_PREFIX + UUID.randomUUID();
        AtomicReference<RoutingDecision> captured = new AtomicReference<>();
        AtomicReference<Long> auditRowsWhenFired = new AtomicReference<>(-1L);

        routingGraph.run(
                new RoutingRequest(convId, userId, null, "USER", "我要转人工"),
                decision -> {
                    captured.set(decision);
                    // 回调触发的那一刻就去数审计行——这能证明"先审计、后回调"
                    auditRowsWhenFired.set(auditLogMapper.selectCount(
                            Wrappers.<AiAuditLog>lambdaQuery()
                                    .eq(AiAuditLog::getConversationId, convId)));
                });

        assertThat(captured.get())
                .as("回调没被触发的话，前端就收不到 route 事件了")
                .isNotNull();
        assertThat(captured.get().stage()).isEqualTo(RouteStage.HANDOFF);
        assertThat(auditRowsWhenFired.get())
                .as("回调触发时审计应当已经落库——前端看到的和审计记下的必须是同一份判定")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("回调为 null 时不报错（非 SSE 的那条入口就是这么调的）")
    void nullCallbackIsFine() {
        String convId = TEST_CONVERSATION_PREFIX + UUID.randomUUID();
        RoutingGraph.RoutingResult result = routingGraph.run(
                new RoutingRequest(convId, userId, null, "USER", "我要转人工"), null);

        assertThat(result.decision().stage()).isEqualTo(RouteStage.HANDOFF);
    }
}
