package com.jixiejia.agent.agent;

import com.jixiejia.agent.llm.LlmClients;
import org.springframework.stereotype.Component;

/**
 * 知识 Agent。负责平台规则/FAQ 与行业资讯。
 *
 * <p><b>当前只接通了"资讯"这半边。</b>资讯走工具直查 cms_article（已经能用）；
 * 而"平台规则/流程"那条线需要 RAG 知识库 + 证据闸，排在 M6，现在知识库表还是空的。
 *
 * <p>正因如此，这个 Agent 的提示词里有一条硬约束：<b>规则类问题一律不许凭常识回答</b>。
 * 大模型对"电商平台一般怎么办"是有先验的，不按住它就会一本正经地编出押金比例、
 * 手续费率、退款时限——这些编出来的数字比说"不知道"危险得多。
 */
@Component
public class KnowledgeAgent extends AbstractReactAgent {

    public static final String KEY = "KnowledgeAgent";

    /** 规则类问题在知识库接通前的统一话术 */
    private static final String POLICY_NOT_READY =
            "这部分知识库还在建设中，我不想凭印象给你一个可能不准的说法。"
                    + "建议你回复\"转人工\"，让客服给你准确答复。";

    public KnowledgeAgent(LlmClients clients) {
        super(clients);
    }

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    protected String systemPrompt() {
        return """
                你是「机械家」二手工程机械平台的知识助手，负责行业资讯和平台规则两类问题。

                关于**行业资讯**：你可以用工具检索平台发布的资讯、评测、保养维修类文章，
                拿到标题和摘要回答用户；用户想读全文时再取正文。

                关于**平台规则、流程、押金、手续费、退款**这类问题：
                平台规则知识库**目前还没有接入**，你没有任何可信资料来源。
                因此**绝对不要**根据常识或对其它电商平台的印象回答这类问题——
                编造出来的押金比例、手续费率、退款时限会直接误导用户。
                遇到这类问题，如实说明知识库还在建设中，并建议用户转人工。

                必须遵守：
                1. 只依据工具返回的内容回答，文章标题、作者、观点都不要编。
                2. 资讯正文是从 HTML 剥出来的纯文本，如果为空说明该文章只有图片，
                   如实说明即可，不要脑补内容。
                3. 检索为空就说没找到相关资讯，并建议换个关键词。
                4. 回答用口语，资讯类一次最多列 3 到 5 条。
                """;
    }

    @Override
    protected String fallbackReply() {
        return "抱歉，知识库这会儿查不了，请稍后再试。如果是平台规则类问题，建议回复\"转人工\"让客服答复。";
    }

    /** 供上层在知识库接通前直接给出规则类话术。 */
    public String policyNotReadyReply() {
        return POLICY_NOT_READY;
    }
}
