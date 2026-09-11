package com.jixiejia.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 启动后预热本地小模型。
 *
 * <p>背景（实际踩到的坑）：Ollama 第一次调用某个模型时要把几 GB 权重读进内存，
 * 冷启动十几秒很正常。而我们的分类层有超时保护，首个请求往往就撞在冷启动上——
 * 小模型超时、主模型那层也可能慢，两层都没结论，这句话就被判成 UNKNOWN、
 * 静默降级到兜底 Agent。用户只觉得"答得敷衍"，从现象上看不出是超时。
 *
 * <p>所以启动后主动发一次极短的请求把模型加载起来，让第一个真实用户
 * 不必承担这个代价。放在线程池里异步做，不阻塞应用启动；
 * 失败也不管——Ollama 没起时预热当然会失败，那时本来就用不上小模型。
 */
@Slf4j
@Component
public class SmallModelWarmup {

    private static final long WARMUP_TIMEOUT_MS = 120_000L;

    /** 预热用的提示词越短越好，目的只是触发模型加载 */
    private static final String PING = "hi";

    private final LlmClients clients;
    private final ModelCaller modelCaller;
    private final ThreadPoolTaskExecutor chatExecutor;

    public SmallModelWarmup(LlmClients clients,
                            ModelCaller modelCaller,
                            @Qualifier("chatExecutor") ThreadPoolTaskExecutor chatExecutor) {
        this.clients = clients;
        this.modelCaller = modelCaller;
        this.chatExecutor = chatExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        chatExecutor.execute(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                ChatClient small = clients.small();
                String reply = modelCaller.call(small, null, PING, WARMUP_TIMEOUT_MS);
                if (reply != null) {
                    log.info("本地小模型预热完成，耗时 {} ms", System.currentTimeMillis() - startedAt);
                } else {
                    log.info("本地小模型预热未成功（Ollama 可能没启动，不影响其它功能）");
                }
            } catch (Exception e) {
                log.debug("小模型预热异常：{}", e.toString());
            }
        });
    }
}
