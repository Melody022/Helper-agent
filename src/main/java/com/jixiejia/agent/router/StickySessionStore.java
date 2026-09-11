package com.jixiejia.agent.router;

import com.jixiejia.agent.classify.Intent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * 会话粘性。用户连续追问"那这个呢""有便宜点的吗"时，不应该每句都重新分类、
 * 重新挑 Agent——上一轮既然选中了 RentalAgent，这一轮大概率还是它。
 *
 * <p>存储放 Redis 而不是进程内存：多实例部署时同一会话的请求可能落到不同节点，
 * 放本地内存会导致粘性时灵时不灵，这类 bug 很难查。
 *
 * <p>TTL 默认 30 分钟（可配）。过期即回到完整路由链重新分类。
 * {@code /reset} 命令会显式清除。
 *
 * <p>Redis 不可用时不影响对话：读写都吞掉异常当作"没有粘性"，
 * 退化成每轮重新分类——功能变弱但不中断。
 */
@Slf4j
@Component
public class StickySessionStore {

    private static final String KEY_PREFIX = "ai:sticky:";

    /** 粘性记录。 */
    public record StickySession(String agentKey, String intent, long updatedAt) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper json = JsonMapper.builder().build();

    private final boolean enabled;
    private final Duration ttl;

    public StickySessionStore(StringRedisTemplate redis,
                              @Value("${routing.sticky.enabled:true}") boolean enabled,
                              @Value("${routing.sticky.ttl-minutes:30}") long ttlMinutes) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** 记录本轮命中的 Agent 与意图。 */
    public void save(String conversationId, String agentKey, Intent intent) {
        if (!enabled || conversationId == null || agentKey == null) {
            return;
        }
        try {
            String payload = json.writeValueAsString(new StickySession(
                    agentKey, intent == null ? null : intent.name(), System.currentTimeMillis()));
            redis.opsForValue().set(key(conversationId), payload, ttl);
        } catch (Exception e) {
            log.warn("写会话粘性失败（不影响对话）：{}", e.toString());
        }
    }

    /** 读取未过期的粘性记录。 */
    public Optional<StickySession> find(String conversationId) {
        if (!enabled || conversationId == null) {
            return Optional.empty();
        }
        try {
            String payload = redis.opsForValue().get(key(conversationId));
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            JsonNode node = json.readTree(payload);
            return Optional.of(new StickySession(
                    node.path("agentKey").asText(null),
                    node.path("intent").asText(null),
                    node.path("updatedAt").asLong(0)));
        } catch (Exception e) {
            log.warn("读会话粘性失败（当作无粘性）：{}", e.toString());
            return Optional.empty();
        }
    }

    /** 清除粘性，用于 /reset。 */
    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            redis.delete(key(conversationId));
        } catch (Exception e) {
            log.warn("清除会话粘性失败：{}", e.toString());
        }
    }

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
