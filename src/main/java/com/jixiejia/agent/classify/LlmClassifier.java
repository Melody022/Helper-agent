package com.jixiejia.agent.classify;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 第 3 层：MIMO 大模型分类，路由链上最贵的一环，只在前面都没定案时才走。
 */
@Component
public class LlmClassifier extends AbstractModelClassifier {

    /** 主模型是推理型，会先"想"一段再输出，超时要比普通对话宽 */
    private static final long DEFAULT_TIMEOUT_MS = 45_000L;

    public LlmClassifier(LlmClients clients, ModelCaller modelCaller,
                         @Value("${routing.llm.timeout-ms:45000}") long timeoutMs) {
        super(clients, modelCaller, timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS);
    }

    @Override
    protected ChatClient client() {
        return clients.main();
    }

    @Override
    public ClassifyLayer layer() {
        return ClassifyLayer.LLM;
    }

    @Override
    public String layerName() {
        return "大模型(MIMO)";
    }
}
