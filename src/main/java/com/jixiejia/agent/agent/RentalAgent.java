package com.jixiejia.agent.agent;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.PromptLibrary;
import org.springframework.stereotype.Component;

/**
 * 租赁 Agent。负责出租、求租两件事——方向正好相反，别搞混：
 * "有机器的要往外租"（search_chuzu）vs "要用机器的人在找机器"（search_qiuzu）。
 *
 * <p>以前这里还有"用机需求"和"新机询价"两件事，后来拆了：
 * 用机需求**就是求租**（同一个业务概念，平台上只是存了两张表），
 * 合成一个工具一次查；新机询价是"有人想买"，归到设备买卖那边去了。
 *
 * <p>提示词里把每个工具的语义讲透了，见 {@code classpath:prompts/rental-agent.md}。
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
