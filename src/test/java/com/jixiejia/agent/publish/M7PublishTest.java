package com.jixiejia.agent.publish;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.agent.AgentContext;
import com.jixiejia.agent.agent.AgentExecutor;
import com.jixiejia.agent.agent.BizAgent;
import com.jixiejia.agent.persistence.entity.ai.AiPublishRequest;
import com.jixiejia.agent.persistence.entity.ai.AiUser;
import com.jixiejia.agent.persistence.entity.jxj.JxbChuzu;
import com.jixiejia.agent.persistence.mapper.ai.AiPublishRequestMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiUserMapper;
import com.jixiejia.agent.persistence.mapper.jxj.JxbChuzuMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7 发布工作流测试。
 *
 * <p>发布是全项目里唯一会写业务表的链路，所以测试要盯住三件事：
 * <ol>
 *   <li><b>不该写的时候绝不写</b>——信息没收集全、或者用户没确认，业务表里不能多出任何行；</li>
 *   <li><b>落库的内容必须对</b>——写进去的字段值和用户确认过的一致；</li>
 *   <li><b>audit_status 必须是待审核</b>——AI 代发不能替平台做审核决定。</li>
 * </ol>
 *
 * <p>测试自己插入的发布记录会在用例结束后物理删除，不留痕。
 */
@SpringBootTest
class M7PublishTest {

    /** 测试用的会话前缀，便于清理 */
    private static final String CONV_PREFIX = "test-pub-";

    @Autowired
    private AgentExecutor agentExecutor;

    @Autowired
    private PublishFormService formService;

    @Autowired
    private PublishValueValidator validator;

    @Autowired
    private AiUserMapper userMapper;

    @Autowired
    private AiPublishRequestMapper requestMapper;

    @Autowired
    private JxbChuzuMapper chuzuMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long memberId;
    private String conversationId;

    /** 模拟连续对话：每轮把用户和助手的消息都累积起来，下一轮带进去 */
    private final List<Message> history = new ArrayList<>();

    @BeforeEach
    void setUp() {
        conversationId = CONV_PREFIX + UUID.randomUUID();
        history.clear();

        AiUser user = new AiUser();
        user.setUsername("test_pub_" + UUID.randomUUID());
        user.setPassword("x");
        user.setStatus("0");
        user.setDelFlag("0");
        userMapper.insert(user);
        userId = user.getId();

        // 绑一个真实会员，否则落库时会被"账号未绑定会员"拦下
        memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM ums_member WHERE del_flag = '0' LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO ai_publish_mapping (user_id, member_id) VALUES (?, ?)",
                userId, memberId);
    }

    @AfterEach
    void cleanUp() {
        // 先删落库的业务行（它们挂在测试会员名下），再删草稿与账号
        jdbcTemplate.update("DELETE FROM jxb_chuzu WHERE customer_id = ? AND audit_status = '0' "
                + "AND create_time > DATE_SUB(NOW(), INTERVAL 10 MINUTE)", memberId);
        jdbcTemplate.update("DELETE FROM ai_publish_request WHERE LEFT(conversation_id, 9) = 'test-pub-'");
        jdbcTemplate.update("DELETE FROM ai_publish_mapping WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM ai_user WHERE LEFT(username, 5) = 'test_'");
    }

    private BizAgent publishAgent() {
        return agentExecutor.find("PublishAgent").orElseThrow();
    }

    private String say(String text) {
        String reply = publishAgent().reply(new AgentContext(
                conversationId, userId, memberId, "USER",
                text, List.copyOf(history), List.of()));
        history.add(new UserMessage(text));
        history.add(new AssistantMessage(reply));
        return reply;
    }

    /**
     * 库里这个会员名下待审核的出租条数。
     *
     * <p>注意这里不能用 {@code BizFilters.visibleChuzu()}：那个筛的是
     * audit_status='1'（已通过）——待审核的行本来就不是"对用户可见"的数据。
     * 用它去查待审核记录，永远查不到，测试会假绿。
     */
    private long pendingChuzuCount() {
        return chuzuMapper.selectCount(Wrappers.<JxbChuzu>lambdaQuery()
                .eq(JxbChuzu::getCustomerId, memberId)
                .eq(JxbChuzu::getAuditStatus, "0"));
    }

    // ---------------- 纯逻辑：值校验（不调模型） ----------------

    @Test
    @DisplayName("校验：手机号、年份要做格式检查")
    void validatesPhoneAndYear() {
        PublishField phone = PublishTarget.CHUZU.field("phone");
        assertThat(validator.validate(phone, "13811334488").ok()).isTrue();
        assertThat(validator.validate(phone, "138-1133-4488").ok())
                .as("带分隔符也应当能识别").isTrue();
        assertThat(validator.validate(phone, "12345").ok()).isFalse();

        PublishField year = PublishTarget.CHUZU.field("factoryYear");
        assertThat(validator.validate(year, "2018").ok()).isTrue();
        assertThat(validator.validate(year, "11111").ok())
                .as("库里真实存在这种脏数据，必须在入口拦住").isFalse();
        assertThat(validator.validate(year, "1800").ok()).isFalse();
    }

    @Test
    @DisplayName("校验：字典值中文和码值都认，认不出来要报错而不是硬塞")
    void validatesDictValues() {
        PublishField tonnage = PublishTarget.CHUZU.field("tonnage");

        PublishValueValidator.Outcome byLabel = validator.validate(tonnage, "6-9吨");
        assertThat(byLabel.ok()).isTrue();
        assertThat(byLabel.value()).isEqualTo("6-9");

        PublishValueValidator.Outcome byCode = validator.validate(tonnage, "6-9");
        assertThat(byCode.ok()).isTrue();

        PublishValueValidator.Outcome bad = validator.validate(tonnage, "特别大");
        assertThat(bad.ok()).isFalse();
        assertThat(bad.error()).contains("可选");
    }

    @Test
    @DisplayName("校验：地区要把口语地名解析成区划码，解析不出就不能往下走")
    void validatesRegion() {
        PublishField area = PublishTarget.CHUZU.field("area");

        PublishValueValidator.Outcome ok = validator.validate(area, "洛阳");
        assertThat(ok.ok()).isTrue();
        assertThat(PublishValueValidator.regionPart(ok.value(), "provinceId")).isNotNull();
        assertThat(PublishValueValidator.regionPart(ok.value(), "cityId")).isNotNull();

        PublishValueValidator.Outcome bad = validator.validate(area, "火星");
        assertThat(bad.ok()).isFalse();
    }

    // ---------------- 工作流：不该写的时候绝不写 ----------------

    @Test
    @DisplayName("未确认前不落库：只说要发布、信息不全时，业务表里一行都不能多")
    void doesNotWriteBeforeConfirmation() {
        long before = pendingChuzuCount();

        String reply = say("帮我发布一台出租");

        assertThat(reply).contains("还需要你补充");
        assertThat(pendingChuzuCount())
                .as("信息没收集全就把数据写进去，是发布流程最危险的 bug")
                .isEqualTo(before);

        // 但草稿应当已经建起来了
        AiPublishRequest draft = formService.findActiveDraft(conversationId);
        assertThat(draft).isNotNull();
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("信息填全后给出确认摘要和令牌，但此时仍未落库")
    void asksForConfirmationWithoutWriting() {
        long before = pendingChuzuCount();

        say("帮我发布一台出租");
        String summary = say("机型是挖掘机，吨位 20-29吨，在洛阳市，租金 1000/天 不带油，"
                + "我是公司出租，电话 13811334488");

        System.out.println("[确认摘要]\n" + summary);

        assertThat(summary).contains("请核对");
        assertThat(summary).contains("确认发布");
        // 摘要里必须是中文，不能把码值或区划码直接甩给用户核对
        assertThat(summary).contains("挖掘机").contains("20-29吨").contains("公司");
        assertThat(summary).doesNotContain("110000000000");

        assertThat(pendingChuzuCount())
                .as("给了确认摘要但用户还没确认，不能落库")
                .isEqualTo(before);

        AiPublishRequest draft = formService.findActiveDraft(conversationId);
        assertThat(formService.isAwaitingConfirm(draft)).isTrue();
        assertThat(draft.getConfirmToken()).isNotBlank();
    }

    // ---------------- 确认后才落库 ----------------

    @Test
    @DisplayName("确认后落库：写入待审核状态，字段值与确认的一致，且不能重复提交")
    void writesAfterConfirmation() {
        long before = pendingChuzuCount();

        say("帮我发布一台出租");
        String summary = say("机型是挖掘机，吨位 20-29吨，在洛阳市，租金 1000/天 不带油，"
                + "我是公司出租，电话 13811334488");

        AiPublishRequest draft = formService.findActiveDraft(conversationId);
        String token = draft.getConfirmToken();

        String result = say("确认发布 " + token);
        System.out.println("[提交结果]\n" + result);

        assertThat(result).contains("已提交");
        assertThat(pendingChuzuCount()).isEqualTo(before + 1);

        // 落库内容核对
        JxbChuzu saved = chuzuMapper.selectOne(Wrappers.<JxbChuzu>lambdaQuery()
                .eq(JxbChuzu::getCustomerId, memberId)
                .eq(JxbChuzu::getAuditStatus, "0"));
        assertThat(saved).isNotNull();
        assertThat(saved.getTonnage()).isEqualTo("20-29");
        assertThat(saved.getOwnerType()).isEqualTo("gs");
        assertThat(saved.getRent()).contains("1000/天");
        assertThat(saved.getPhone()).isEqualTo("13811334488");
        assertThat(saved.getProvinceId()).isNotNull();
        assertThat(saved.getCityId()).isNotNull();
        // 关键：AI 代发必须进待审核，不能替平台做审核决定
        assertThat(saved.getAuditStatus()).isEqualTo("0");
        assertThat(saved.getDelFlag()).isEqualTo("0");

        // 草稿状态与落库结果对上了
        AiPublishRequest updated = requestMapper.selectById(draft.getId());
        assertThat(updated.getStatus()).isEqualTo("SUBMITTED");
        assertThat(updated.getBizId()).isEqualTo(saved.getId());
        // 令牌一次性：用完即弃，防止同一条确认被重放提交两次
        assertThat(updated.getConfirmToken()).isNull();
    }

    @Test
    @DisplayName("取消：用户说取消后草稿作废，不落库")
    void cancelsWithoutWriting() {
        long before = pendingChuzuCount();

        say("帮我发布一台出租");
        say("机型是挖掘机，吨位 20-29吨，在洛阳市，租金 1000/天，公司出租，电话 13811334488");
        String reply = say("算了，先不发了");

        assertThat(reply).contains("取消");
        assertThat(pendingChuzuCount()).isEqualTo(before);
        assertThat(formService.findActiveDraft(conversationId))
                .as("取消后不该再有活跃草稿")
                .isNull();
    }
}
