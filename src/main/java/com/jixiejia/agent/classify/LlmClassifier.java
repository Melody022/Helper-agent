package com.jixiejia.agent.classify;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 第 3 层：MIMO 大模型分类，路由链上最贵的一环，只在前面都没定案时才走。
 */
@Component
public class LlmClassifier extends AbstractModelClassifier {

    public LlmClassifier(LlmClients clients, ModelCaller modelCaller) {
        super(clients, modelCaller);
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
