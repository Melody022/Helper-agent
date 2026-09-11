package com.jixiejia.agent.agent;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.PromptLibrary;
import org.springframework.stereotype.Component;

/**
 * 租赁 Agent。负责出租、求租、用机需求、新机询价四件事。
 *
 * <p>这四件事方向容易搞混（"有人要租机器"和"机主要找活"完全是反的），
 * 提示词里把每个工具的语义讲透了，见 {@code classpath:prompts/rental-agent.md}。
 */
@Component
public class RentalAgent extends AbstractReactAgent {

    public static final String KEY = "RentalAgent";

    public RentalAgent(LlmClients clients, PromptLibrary prompts) {
        super(clients, prompts);
    }

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    public String promptKey() {
        return "rental-agent";
    }

    @Override
    protected String fallbackReply() {
        return "抱歉，租赁信息这会儿查不出来，请稍后再试，或者换个说法问，比如\"附近有挖掘机出租吗\"。";
    }
}
