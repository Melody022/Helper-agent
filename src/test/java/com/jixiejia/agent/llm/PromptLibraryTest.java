package com.jixiejia.agent.llm;

import com.jixiejia.agent.agent.AgentExecutor;
import com.jixiejia.agent.agent.AbstractReactAgent;
import com.jixiejia.agent.agent.BizAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词文件完整性测试。
 *
 * <p>提示词从 Java 挪到文件之后多了一类新的失败方式：<b>引用了一个不存在的文件</b>。
 * 代码不会报错——{@link PromptLibrary} 会返回一段保守的兜底话术，
 * Agent 照常回答，只是答得很敷衍。这种"静默降级"从日志和接口上都看不出来，
 * 只能靠测试挡住。
 *
 * <p>所以这里逐个校验：所有被引用的提示词 key 都必须真的有对应的文件。
 */
@SpringBootTest
class PromptLibraryTest {

    /** 代码里引用到的全部提示词 key。新增提示词时同步加到这里。 */
    private static final List<String> REQUIRED_KEYS = List.of(
            "equipment-agent",
            "rental-agent",
            "knowledge-agent",
            "general-agent",
            "intent-classifier",
            "composite-synthesis",
            "knowledge-generation",
            "knowledge-self-eval",
            "reference-resolution");

    @Autowired
    private PromptLibrary prompts;

    @Autowired
    private AgentExecutor agentExecutor;

    @Test
    @DisplayName("所有被引用的提示词文件都真实存在（缺失会静默走兜底话术）")
    void allReferencedPromptsExist() {
        for (String key : REQUIRED_KEYS) {
            assertThat(prompts.has(key))
                    .as("缺少提示词文件 classpath:prompts/%s.md，该处会静默降级成兜底话术", key)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("每个接了模型的 Agent 都能取到真实提示词，而不是兜底话术")
    void everyAgentHasRealPrompt() {
        for (BizAgent agent : collectAgents()) {
            // 发布 Agent 走确定性工作流、不接模型，没有提示词文件
            if (!(agent instanceof AbstractReactAgent reactAgent)) {
                continue;
            }
            String prompt = prompts.get(reactAgent.promptKey());

            assertThat(prompt).as("Agent %s 的提示词", agent.agentKey()).isNotBlank();
            // 兜底话术是给"文件缺失"准备的，出现在这里说明文件没加载上
            assertThat(prompt)
                    .as("Agent %s 拿到的是兜底话术，说明提示词文件没生效", agent.agentKey())
                    .doesNotContain("你暂时无法确定用户的问题");
        }
    }

    @Test
    @DisplayName("提示词正文里不该出现未替换的模板变量")
    void noUnresolvedTemplateVariables() {
        // 意图清单是唯一的模板变量，由代码注入；其余文件不该带 {{ }}
        for (String key : REQUIRED_KEYS) {
            if ("intent-classifier".equals(key)) {
                continue;
            }
            assertThat(prompts.get(key))
                    .as("提示词 %s 里残留了未替换的模板变量", key)
                    .doesNotContain("{{");
        }

        // 意图分类器的变量必须真的会被替换掉
        String rendered = prompts.get("intent-classifier",
                java.util.Map.of("intents", "- EQUIPMENT_QUERY：测试"));
        assertThat(rendered).doesNotContain("{{").contains("EQUIPMENT_QUERY");
    }

    private List<BizAgent> collectAgents() {
        return agentExecutor.agentKeys().stream()
                .map(key -> agentExecutor.find(key).orElseThrow())
                .toList();
    }

}
