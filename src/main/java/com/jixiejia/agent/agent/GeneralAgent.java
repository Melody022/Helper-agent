package com.jixiejia.agent.agent;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.PromptLibrary;
import org.springframework.stereotype.Component;

/**
 * 通用 Agent。负责问候、自我介绍、能力引导，以及一切兜底。
 *
 * <p>它**不挂任何工具**（ai_agent.capabilities 为空），所以拿不到设备数据。
 * 这不是缺陷而是设计：兜底 Agent 一旦能查数据，就会在路由出错时给出
 * 看似可信其实串了领域的答案，掩盖掉真正的路由问题。
 *
 * <p>提示词见 {@code classpath:prompts/general-agent.md}。
 */
@Component
public class GeneralAgent extends AbstractReactAgent {

    public static final String KEY = "GeneralAgent";

    public GeneralAgent(LlmClients clients, PromptLibrary prompts) {
        super(clients, prompts);
    }

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    public String promptKey() {
        return "general-agent";
    }

    @Override
    protected String fallbackReply() {
        return "你好，我是机械家的智能助手，可以帮你找设备、查出租求租、看资讯。"
                + "你想了解什么？如果问题比较具体，可以说得再细一点（比如机型、地区、预算）。";
    }
}
