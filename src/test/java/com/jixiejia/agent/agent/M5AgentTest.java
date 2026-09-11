package com.jixiejia.agent.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiAgent;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.mapper.ai.AiAgentMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import com.jixiejia.agent.router.MsgRouter;
import com.jixiejia.agent.router.RoutingDecision;
import com.jixiejia.agent.router.RoutingRequest;
import com.jixiejia.agent.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 Agent 层测试。
 *
 * <p>两件事分开验：
 * <ol>
 *   <li><b>装配一致性</b>——真跑模型之前，先确保"库里配的 Agent"和"代码里写的 Agent"
 *       完全对得上。这类漂移不会报错，只会让某类问题永远落到兜底，
 *       属于最难发现的一类 bug；</li>
 *   <li><b>真跑一遍</b>——用真实模型 + 真实库跑通"路由 → Agent → 工具 → 回答"。
 *       这部分慢（十几秒），所以只挑代表性的一条，断言也写得宽松
 *       （只要求答出真实数据里的东西，不要求特定措辞）。</li>
 * </ol>
 */
@SpringBootTest
class M5AgentTest {

    @Autowired
    private AgentExecutor agentExecutor;

    @Autowired
    private MsgRouter msgRouter;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AiAgentMapper agentMapper;

    @Autowired
    private AiUserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUpTestUsers() {
        jdbcTemplate.update("DELETE FROM ai_user WHERE LEFT(username, 5) = 'test_'");
    }

    private Long createUser() {
        AiUser user = new AiUser();
        user.setUsername("test_agent_" + UUID.randomUUID());
        user.setPassword("x");
        user.setStatus("0");
        user.setDelFlag("0");
        userMapper.insert(user);
        return user.getId();
    }

    // ---------------- 装配一致性（不调模型，快） ----------------

    @Test
    @DisplayName("装配：5 个 Agent 都装上了，且 key 与 ai_agent 表完全对齐")
    void agentsMatchRegistry() {
        assertThat(agentExecutor.agentKeys()).containsExactlyInAnyOrder(
                "EquipmentAgent", "RentalAgent", "KnowledgeAgent", "PublishAgent", "GeneralAgent");

        // 库里配了但代码没实现 —— 会让这类问题永远落到兜底，必须拦住
        Set<String> inDb = agentMapper.selectList(Wrappers.<AiAgent>lambdaQuery())
                .stream().map(AiAgent::getAgentKey).collect(Collectors.toSet());
        Set<String> inCode = agentExecutor.agentKeys();

        assertThat(inCode)
                .as("代码里的 Agent 与 ai_agent 表必须一一对应")
                .containsExactlyInAnyOrderElementsOf(inDb);
    }

    @Test
    @DisplayName("装配：发布 Agent 在功能上线前只回固定话术，不调模型")
    void publishAgentReturnsFixedScript() {
        BizAgent publish = agentExecutor.find("PublishAgent").orElseThrow();
        String answer = publish.reply("帮我发布一台出租", List.of(), List.of());

        assertThat(answer).contains("开发中");
        // 必须让用户知道"还没提交"，不能给已经发布的错觉
        assertThat(answer).contains("确认");
    }

    @Test
    @DisplayName("装配：兜底 Agent 拿不到任何工具")
    void fallbackAgentHasNoTools() {
        RoutingDecision decision = msgRouter.route(new RoutingRequest(
                "test-" + UUID.randomUUID(), createUser(), null, "USER", "你好呀"));

        assertThat(decision.agentKey()).isEqualTo("GeneralAgent");
        assertThat(decision.toolNames()).isEmpty();
    }

    // ---------------- 真跑一遍（调模型，慢） ----------------

    @Test
    @DisplayName("真跑：设备查询走通路由 → Agent → 工具 → 回答，且数据来自真实库")
    void equipmentAgentAnswersWithRealData() {
        Long userId = createUser();
        RoutingDecision decision = msgRouter.route(new RoutingRequest(
                "test-" + UUID.randomUUID(), userId, null, "USER", "有没有二手的挖掘机"));

        assertThat(decision.agentKey()).isEqualTo("EquipmentAgent");

        ToolCallback[] tools = toolRegistry.callbacksFor(decision.toolNames());
        assertThat(tools).isNotEmpty();

        String answer = agentExecutor
                .execute(decision.agentKey(), decision.resolvedText(), List.of(), List.of(tools))
                .orElseThrow();

        System.out.println("[设备查询回答]\n" + answer);

        assertThat(answer).isNotBlank();
        // 兜底话术意味着 Agent 执行失败，这种情况必须让测试红掉，不能被"有返回"糊弄过去
        assertThat(answer).doesNotContain("不太顺畅").doesNotContain("查询出了点问题");
        // 真实库里的挖掘机名字里带"挖掘机"，答对了必然出现
        assertThat(answer).contains("挖掘机");
    }

    @Test
    @DisplayName("真跑：平台规则问题必须诚实说不知道，不能编规则")
    void knowledgeAgentRefusesToInventPolicy() {
        Long userId = createUser();
        RoutingDecision decision = msgRouter.route(new RoutingRequest(
                "test-" + UUID.randomUUID(), userId, null, "USER", "在平台租设备的流程和押金怎么算"));

        assertThat(decision.agentKey()).isEqualTo("KnowledgeAgent");

        ToolCallback[] tools = toolRegistry.callbacksFor(decision.toolNames());
        String answer = agentExecutor
                .execute(decision.agentKey(), decision.resolvedText(), List.of(), List.of(tools))
                .orElseThrow();

        System.out.println("[平台规则回答]\n" + answer);

        // 知识库还没接入，模型对这类问题是有先验的，必须按住它
        assertThat(answer).isNotBlank();
        assertThat(answer)
                .as("知识库接通前，规则类问题不得给出具体条款或比例")
                .doesNotContain("押金是").doesNotContain("手续费为").doesNotContain("按比例");
    }
}
