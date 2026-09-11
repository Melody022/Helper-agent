package com.jixiejia.agent.classify;

import com.jixiejia.agent.llm.LlmClients;
import com.jixiejia.agent.llm.ModelCaller;
import com.jixiejia.agent.llm.PromptLibrary;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 第 2 层：本地 Ollama 小模型分类。
 *
 * <p>关键词层定不了案时才走这里。用本地小模型而不是直接上大模型，
 * 是因为这类"判别型"任务不需要多少语言能力，qwen2.5:7b 足够，
 * 而且离线、零成本、没有网络抖动——大部分模糊说法到这一层就能收敛。
 */
@Component
public class SmallModelClassifier extends AbstractModelClassifier {

    /** 本地模型冷启动要把几 GB 读进内存，超时给得比别的层宽 */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    public SmallModelClassifier(LlmClients clients, ModelCaller modelCaller, PromptLibrary prompts,
                                @Value("${routing.small-model.timeout-ms:30000}") long timeoutMs) {
        super(clients, modelCaller, prompts, timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS);
    }

    @Override
    protected ChatClient client() {
        return clients.small();
    }

    @Override
    public ClassifyLayer layer() {
        return ClassifyLayer.SMALL_MODEL;
    }

    @Override
    public String layerName() {
        return "小模型(Ollama)";
    }
}
