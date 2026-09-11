package com.jixiejia.agent.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

/**
 * 两个 ChatClient 的装配点。按类型注入而不是按名字，避免以后 Bean 改名就悄悄失效。
 *
 * <p>分工：
 * <ul>
 *   <li>{@code main} = MIMO（OpenAI 兼容），负责意图分类第 3 层与最终回答生成；</li>
 *   <li>{@code small} = 本地 Ollama，负责意图分类第 2 层这类"判别型"小任务。
 *       它便宜、离线、够用，判错了还有第 3 层兜着。</li>
 * </ul>
 *
 * <p>两个模型的 temperature 都在 application.yml 里配好了（分类任务取 0，保证可复现）。
 */
@Component
public class LlmClients {

    private final ChatClient mainClient;
    private final ChatClient smallClient;

    public LlmClients(OpenAiChatModel mainChatModel, OllamaChatModel smallChatModel) {
        this.mainClient = ChatClient.create(mainChatModel);
        this.smallClient = ChatClient.create(smallChatModel);
    }

    /** 主模型客户端（MIMO）。 */
    public ChatClient main() {
        return mainClient;
    }

    /** 小模型客户端（本地 Ollama）。 */
    public ChatClient small() {
        return smallClient;
    }
}
