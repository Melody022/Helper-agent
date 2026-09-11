package com.jixiejia.agent.agent;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.PromptLibrary;
import org.springframework.stereotype.Component;

/**
 * 知识 Agent。负责行业资讯与平台规则。
 *
 * <p>资讯走工具直查 cms_article；平台规则走的是另一条链路
 * （{@link com.jixiejia.agent.rag.KnowledgeAnswerService}：检索 + 证据闸）。
 * 如果规则类问题到了这里，说明检索没给出可靠依据，提示词里明确要求它如实说找不到、
 * 不许凭常识回答——见 {@code classpath:prompts/knowledge-agent.md}。
 */
@Component
public class KnowledgeAgent extends AbstractReactAgent {

    public static final String KEY = "KnowledgeAgent";

    public KnowledgeAgent(LlmClients clients, PromptLibrary prompts) {
        super(clients, prompts);
    }

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    public String promptKey() {
        return "knowledge-agent";
    }

    @Override
    protected String fallbackReply() {
        return "抱歉，知识库这会儿查不了，请稍后再试。如果是平台规则类问题，建议回复\"转人工\"让客服答复。";
    }
}
