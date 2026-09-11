package com.jixiejia.agent.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 发布 Agent。负责"发布出租 / 发布求租"。
 *
 * <p><b>功能尚未实现</b>，按方案排在 M7（确定性工作流 + 确认令牌 + 落库待审核）。
 *
 * <p>这里刻意做成一个不接模型的固定话术，而不是让模型自由发挥：
 * 发布是写操作，模型能做的只是"表达想发布"，真正的落库必须走表单确认那道门。
 * 在门还没造好之前，让模型去聊发布细节只会给用户"已经提交了"的错觉——
 * 这是比功能缺失更糟的体验。所以直接讲清楚还没上线、并给出替代路径。
 */
@Component
public class PublishAgent implements BizAgent {

    public static final String KEY = "PublishAgent";

    private static final String NOT_READY_REPLY = """
            发布功能还在开发中，暂时没法直接帮你在平台发布出租或求租信息。
            你可以这样操作：
            1. 先告诉我设备的型号、吨位、所在地区和期望租金，我帮你记一下要点；
            2. 或者回复"转人工"，让客服协助你完成发布。

            发布上线后，我会先跟你核对一遍信息，你确认无误才会提交，并且提交后需要平台审核。""";

    @Override
    public String agentKey() {
        return KEY;
    }

    @Override
    public String reply(String userText, List<Message> history, List<ToolCallback> tools) {
        return NOT_READY_REPLY;
    }
}
