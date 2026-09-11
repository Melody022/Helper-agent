package com.jixiejia.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 带超时的模型调用封装。
 *
 * <p>存在的理由：模型调用可能卡住——本地 Ollama 在冷启动加载模型时尤其明显，
 * 远端接口也可能不响应。直接同步调用会把请求线程一直占住，
 * 用户看到的是"转圈到天荒地老"，而不是一个明确的降级结果。
 *
 * <p>超时后调用返回 null，由调用方决定降级策略。模型调用放独立守护线程池，
 * 超时后放弃等待（底层 HTTP 请求可能在后台自行结束），不阻塞 JVM 退出。
 */
@Slf4j
@Component
public class ModelCaller {

    /** 默认超时：分类这类小任务超过 8 秒没有意义 */
    public static final long DEFAULT_TIMEOUT_MS = 8000;

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-model-call");
        t.setDaemon(true);
        return t;
    });

    /**
     * 调用模型并等待结果。
     *
     * @param system  系统提示，可为 null
     * @return 模型返回的文本；超时或异常时返回 null
     */
    public String call(ChatClient client, String system, String user, long timeoutMs) {
        if (client == null || user == null) {
            return null;
        }
        try {
            return CompletableFuture
                    .supplyAsync(() -> {
                        ChatClient.ChatClientRequestSpec spec = client.prompt();
                        if (system != null && !system.isBlank()) {
                            spec = spec.system(system);
                        }
                        return spec.user(user).call().content();
                    }, EXECUTOR)
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("模型调用失败或超时（{}ms）：{}", timeoutMs, e.toString());
            return null;
        }
    }

    public String call(ChatClient client, String system, String user) {
        return call(client, system, user, DEFAULT_TIMEOUT_MS);
    }
}
