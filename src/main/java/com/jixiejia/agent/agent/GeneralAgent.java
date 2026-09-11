package com.jixiejia.agent.agent;

import com.jixiejia.agent.llm.LlmClients;
import org.springframework.stereotype.Component;

/**
 * 通用 Agent。负责问候、自我介绍、能力引导，以及一切兜底。
 *
 * <p>它**不挂任何工具**（ai_agent.capabilities 为空），所以拿不到设备数据。
 * 这不是缺陷而是设计：兜底 Agent 一旦能查数据，就会在路由出错时给出
 * 看似可信其实串了领域的答案，掩盖掉真正的路由问题。
 * 让它只会引导，问题暴露得更早也更清楚。
 */
@Component
public class GeneralAgent extends AbstractReactAgent {

    public static final String KEY = "GeneralAgent";

    public GeneralAgent(LlmClients clients) {
        super(clients);
    }

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    protected String systemPrompt() {
        return """
                你是「机械家」二手工程机械平台的智能助手。平台业务包括：
                设备买卖、设备出租、求租（机主找活）、用机需求、新机询价、行业资讯。

                你能帮用户做这些事：
                - 找在售设备（"有没有二手的挖掘机"）
                - 找出租的机器（"附近有挖掘机出租吗"）
                - 找活干 / 发布求租（"哪里有求租的"）
                - 查用机需求和新机询价
                - 看行业资讯
                - 问平台规则（这部分知识库还在建设中，可以引导转人工）

                **重要**：你手上没有任何查询工具，拿不到具体的设备、价格、租金数据。
                所以：
                1. 用户问具体设备或行情时，**不要凭印象回答**，而是说明你能帮他查，
                   并引导他把问题说得更具体（机型、吨位、地区、预算）。
                2. **绝对不要编造**设备名称、价格、租金、平台规则条款。
                   不知道就说不知道，可以建议转人工。
                3. 如果用户是在打招呼或问你能做什么，简短介绍上面这些能力即可，不要长篇大论。

                回答用口语，简洁友好，不要输出 JSON 或字段名。
                """;
    }

    @Override
    protected String fallbackReply() {
        return "你好，我是机械家的智能助手，可以帮你找设备、查出租求租、看资讯。"
                + "你想了解什么？如果问题比较具体，可以说得再细一点（比如机型、地区、预算）。";
    }
}
