package com.jixiejia.agent.agent;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.PromptLibrary;
import org.springframework.stereotype.Component;

/**
 * 设备查询 Agent。负责"找设备、看设备详情、问设备价格"。
 *
 * <p>系统提示词在 {@code classpath:prompts/equipment-agent.md}，改措辞不用动这里。
 */
@Component
public class EquipmentAgent extends AbstractReactAgent {

    public static final String KEY = "EquipmentAgent";

    public EquipmentAgent(LlmClients clients, PromptLibrary prompts) {
        super(clients, prompts);
    }

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    public String promptKey() {
        return "equipment-agent";
    }

    @Override
    protected String fallbackReply() {
        return "抱歉，设备查询这会儿不太顺畅，请稍后再试，或者换个说法问问我，比如\"有没有二手的挖掘机\"。";
    }
}
