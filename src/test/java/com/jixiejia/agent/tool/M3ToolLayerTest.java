package com.jixiejia.agent.tool;

import com.jixiejia.agent.persistence.entity.ai.AiTool;
import com.jixiejia.agent.persistence.mapper.ai.AiRoleToolMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiToolMapper;
import com.jixiejia.agent.persistence.entity.ai.AiRoleTool;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 工具层测试：直接调用工具方法（不经模型），验证"查库 → 字典翻译 → 地区解析 → JSON"整条链路。
 *
 * <p>重点不是"SQL 能不能跑"，而是<b>交给模型的内容对不对</b>：
 * 出参里必须是中文标签而不是 dz/gcqfk 这类码值，地区必须是"河南省洛阳市"而不是 12 位数字。
 * 这两件事错了，模型再聪明也只能编。
 */
@SpringBootTest
class M3ToolLayerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private EquipmentQueryTool equipmentQueryTool;

    @Autowired
    private ChuzuQueryTool chuzuQueryTool;

    @Autowired
    private QiuzuQueryTool qiuzuQueryTool;

    @Autowired
    private XunjiaQueryTool xunjiaQueryTool;

    @Autowired
    private NewsQueryTool newsQueryTool;

    @Autowired
    private AiToolMapper aiToolMapper;

    @Autowired
    private AiRoleToolMapper aiRoleToolMapper;

    private JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("自动注册：8 个 @Tool 方法全部被发现并同步进 ai_tool")
    void autoRegistration() {
        assertThat(toolRegistry.allToolNames()).containsExactlyInAnyOrder(
                "search_equipment", "get_equipment_detail",
                "search_chuzu", "search_qiuzu", "search_xunjia",
                "search_news", "get_news_detail");

        // 同步到 ai_tool 后，每行都应带上能力标识与入参 schema
        for (ToolRegistry.ToolDescriptor d : toolRegistry.descriptors()) {
            assertThat(d.capability()).isNotBlank();
            assertThat(d.description()).isNotBlank();
            assertThat(d.inputSchema()).contains("\"type\"").contains("properties");
        }

        Long dbCount = aiToolMapper.selectCount(
                Wrappers.<AiTool>lambdaQuery().eq(AiTool::getStatus, "0"));
        assertThat(dbCount).isEqualTo(toolRegistry.allToolNames().size());
    }

    @Test
    @DisplayName("角色白名单：ADMIN/USER 默认获得全部工具")
    void roleWhitelist() {
        Set<String> admin = toolRegistry.toolNamesForRole("ADMIN");
        Set<String> user = toolRegistry.toolNamesForRole("USER");

        assertThat(admin).hasSize(toolRegistry.allToolNames().size());
        assertThat(user).hasSize(toolRegistry.allToolNames().size());

        // 角色授权落到了 ai_role_tool
        assertThat(aiRoleToolMapper.selectCount(Wrappers.<AiRoleTool>lambdaQuery())).isPositive();

        // 不存在的角色不应报错，返回空集合
        assertThat(toolRegistry.toolNamesForRole("NO_SUCH_ROLE")).isEmpty();
    }

    @Test
    @DisplayName("能力→工具：按 capability 能取到对应工具")
    void capabilityMapping() {
        // 新机询价是"想买新机"的线索，归在设备买卖这一侧
        assertThat(toolRegistry.toolNamesForCapabilities(Set.of(ToolCapability.EQUIPMENT)))
                .containsExactlyInAnyOrder("search_equipment", "get_equipment_detail", "search_xunjia");

        assertThat(toolRegistry.toolNamesForCapabilities(Set.of(ToolCapability.CHUZU)))
                .containsExactly("search_chuzu");

    }

    @Test
    @DisplayName("设备查询：分类名与标签码已翻成中文")
    void searchEquipment() throws Exception {
        JsonNode root = parse(equipmentQueryTool.searchEquipment("挖掘机", null, null, null, null, null, null, 5));

        assertThat(root.get("total").asInt()).isPositive();
        JsonNode first = root.get("items").get(0);
        assertThat(first.get("machine").asText()).isEqualTo("挖掘机");
        assertThat(first.get("priceWanYuan").isNumber()).isTrue();

        // 标签码 pttj/yxhc 必须变成中文，不能原样透传
        JsonNode tags = first.get("tags");
        assertThat(tags.isArray()).isTrue();
        for (JsonNode tag : tags) {
            assertThat(tag.asText()).isNotIn("pttj", "yxhc", "jjjs", "tjjl");
        }

        // 详情工具
        long id = first.get("id").asLong();
        JsonNode detail = parse(equipmentQueryTool.getEquipmentDetail(id));
        assertThat(detail.get("item").get("id").asLong()).isEqualTo(id);
        assertThat(detail.get("item").get("name").asText()).isNotBlank();
    }

    @Test
    @DisplayName("出租查询：地区口语解析 + 字典码翻译")
    void searchChuzu() throws Exception {
        // "洛阳" 应被解析成 410300000000，且回显中文地名
        JsonNode root = parse(chuzuQueryTool.searchChuzu(null, null, null, null, "洛阳", 20));

        assertThat(root.get("total").asInt()).isEqualTo(5);
        assertThat(root.get("note").asText()).contains("洛阳市");

        JsonNode first = root.get("items").get(0);
        assertThat(first.get("location").asText()).contains("河南省洛阳市");
        // 状态存的是 dz/ml，出参必须是中文
        assertThat(first.get("status").asText()).isIn("待租", "忙碌");
        assertThat(first.get("ownerType").asText()).isIn("个人", "公司");

        // 吨位参数模型可能填中文，也可能填码值 20-29，两种都要认
        JsonNode byLabel = parse(chuzuQueryTool.searchChuzu(null, "10-19吨", null, null, null, 20));
        JsonNode byCode = parse(chuzuQueryTool.searchChuzu(null, "10-19", null, null, null, 20));
        assertThat(byLabel.get("total").asInt()).isEqualTo(byCode.get("total").asInt());
        assertThat(byLabel.get("total").asInt()).isPositive();

        for (JsonNode item : byLabel.get("items")) {
            assertThat(item.get("tonnage").asText()).isEqualTo("10-19吨");
        }
    }

    @Test
    @DisplayName("求租查询：一次查两张表（求租 + 用机需求，同一个业务概念）")
    void searchQiuzu() throws Exception {
        // 求租表 3 条 + 用机需求表 6 条。它们是同一件事，只是当初建了两张表，
        // 所以挂在同一个工具下、一次查完（详见 QiuzuQueryTool 的类注释）
        JsonNode all = parse(qiuzuQueryTool.searchQiuzu(null, null, null, null, null, 20));
        assertThat(all.get("total").asInt()).isEqualTo(9);

        // 返回里要标出来源，否则看不出这条来自哪张表
        StringBuilder sources = new StringBuilder();
        for (JsonNode item : all.get("items")) {
            sources.append(item.get("source").asText()).append(',');
        }
        assertThat(sources.toString()).contains("求租").contains("用机需求");

        JsonNode xwz = parse(qiuzuQueryTool.searchQiuzu("旋挖钻", null, null, null, null, 20));
        assertThat(xwz.get("total").asInt()).isEqualTo(2);
        for (JsonNode item : xwz.get("items")) {
            assertThat(item.get("equipmentType").asText()).isEqualTo("旋挖钻");
        }

        // 不存在的设备类型应给出提示而不是静默当作不过滤
        JsonNode bogus = parse(qiuzuQueryTool.searchQiuzu("宇宙飞船", null, null, null, null, 20));
        assertThat(bogus.get("note").asText()).contains("未识别设备类型");
    }

    @Test
    @DisplayName("求租合并用机需求：一边字典认不出的设备类型要**跳过**那一边，不是不过滤")
    void mergedQiuzuSkipsSourceThatCannotFilter() throws Exception {
        // "旋挖钻"只在求租表的字典（zl_sblx）里，需求表的字典（req_equipment_type）没有。
        // 如果对需求表"不过滤"，用户问旋挖钻就会捞回一堆无关的需求——
        // 那是比"少给几条"更糟的错，所以这里必须跳过并说明。
        JsonNode xwz = parse(qiuzuQueryTool.searchQiuzu("旋挖钻", null, null, null, null, 20));

        for (JsonNode item : xwz.get("items")) {
            assertThat(item.get("source").asText()).isEqualTo("求租");
        }
        assertThat(xwz.get("note").asText()).contains("未查该来源");
    }

    @Test
    @DisplayName("新机询价：归在设备买卖侧（那是有人想买新机），表为空时给明确说明")
    void searchXunjia() throws Exception {
        JsonNode xunjia = parse(xunjiaQueryTool.searchXunjia(null, null, 20));
        assertThat(xunjia.get("total").asInt()).isZero();
        assertThat(xunjia.get("note").asText()).contains("暂无");
    }

    @Test
    @DisplayName("资讯查询：正文 HTML 已剥离")
    void searchNews() throws Exception {
        JsonNode root = parse(newsQueryTool.searchNews("挖掘机", 5));
        assertThat(root.get("total").asInt()).isPositive();

        JsonNode items = root.get("items");
        assertThat(items.size()).isPositive();

        // 不是每篇都有正文——库里有若干篇正文只有一张图，剥完 HTML 标签就是空的，
        // summary 会被 NON_NULL 省略。这里只要求"有正文的那些"确实被剥干净了。
        boolean sawSummary = false;
        for (JsonNode item : items) {
            assertThat(item.get("title").asText()).isNotBlank();
            JsonNode summary = item.get("summary");
            if (summary != null) {
                sawSummary = true;
                assertThat(summary.asText())
                        .doesNotContain("<p>").doesNotContain("<img").doesNotContain("</");
            }
        }
        assertThat(sawSummary).as("至少应有一篇带正文的资讯").isTrue();

        // 取一篇确定有正文的文章验证剥标签
        JsonNode detail = parse(newsQueryTool.getNewsDetail(7L));
        JsonNode content = detail.get("item").get("content");
        assertThat(content).isNotNull();
        assertThat(content.asText()).isNotBlank().doesNotContain("<p>").doesNotContain("</");
    }

    @Test
    @DisplayName("条数上限：limit 被夹到 20 以内")
    void limitIsCapped() throws Exception {
        JsonNode root = parse(equipmentQueryTool.searchEquipment(null, null, null, null, null, null, null, 999));
        assertThat(root.get("items").size()).isLessThanOrEqualTo(20);

        // 缺省时取 5
        JsonNode defaulted = parse(equipmentQueryTool.searchEquipment(null, null, null, null, null, null, null, null));
        assertThat(defaulted.get("items").size()).isLessThanOrEqualTo(5);
    }
}
