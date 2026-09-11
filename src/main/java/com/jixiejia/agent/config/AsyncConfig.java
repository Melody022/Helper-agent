package com.jixiejia.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 聊天用的异步线程池。
 *
 * <p>SSE 场景下请求线程必须立刻返回 SseEmitter，真正的推理在执行线程上跑，
 * 而一次 Agent 调用可能持续几十秒，所以：
 * <ul>
 *   <li>队列容量给足，否则突发流量会直接抛 RejectedExecutionException；</li>
 *   <li>拒绝策略用 CallerRuns——兜不住时让调用线程自己跑，
 *       退化成"慢但一定执行"，比直接失败对用户友好。</li>
 * </ul>
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "chatExecutor")
    public ThreadPoolTaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-chat-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
