package com.jixiejia.agent.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 登录令牌的存取。令牌与"当前用户快照"一起放 Redis，带 TTL。
 *
 * <p>为什么存快照而不是只存 userId：每个请求都要判角色，若每次回查数据库，
 * 一次对话会有好几轮请求、每轮都多两三条 SQL。用户停用/改角色时用
 * {@link #revokeByUserId} 主动清掉该用户所有令牌即可，时效性靠主动失效保证，
 * 不靠每次回查。
 *
 * <p>刻意不引入 Spring Security 全家桶（pom 里只有 spring-security-crypto 用于 BCrypt）：
 * 本项目的鉴权需求就是"有没有登录 + 是不是管理员"，用拦截器 + 注解足够，
 * 引一整套 Security 反而要写一堆和它对抗的配置。
 */
@Slf4j
@Component
public class TokenStore {

    private static final String KEY_PREFIX = "ai:token:";

    /** 记录某用户签发过哪些令牌，便于停用/改角色时批量失效 */
    private static final String USER_INDEX_PREFIX = "ai:token:uid:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final Duration ttl;

    public TokenStore(StringRedisTemplate redis,
                      @Value("${ai.auth.token-ttl-hours:168}") long ttlHours) {
        this.redis = redis;
        this.ttl = Duration.ofHours(ttlHours);
    }

    /** 签发令牌。 */
    public String issue(CurrentUser user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            redis.opsForValue().set(key(token), write(user), ttl);
            redis.opsForSet().add(userIndexKey(user.userId()), token);
            redis.expire(userIndexKey(user.userId()), ttl);
        } catch (Exception e) {
            // 发不出令牌就必须让登录失败，否则用户拿着一个查不到的令牌反复 401
            throw new IllegalStateException("令牌签发失败", e);
        }
        return token;
    }

    /** 解析令牌。Redis 不可用时返回 empty（表现为未登录，而不是放行）。 */
    public Optional<CurrentUser> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String payload = redis.opsForValue().get(key(token));
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(read(payload));
        } catch (Exception e) {
            log.warn("令牌解析失败（按未登录处理）：{}", e.toString());
            return Optional.empty();
        }
    }

    /** 注销单个令牌。 */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            redis.delete(key(token));
        } catch (Exception e) {
            log.warn("注销令牌失败：{}", e.toString());
        }
    }

    /**
     * 让某个用户的全部令牌立即失效。
     * 停用账号、调整角色后应当调用，否则最长要等 TTL 过期才生效。
     */
    public void revokeByUserId(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            String indexKey = userIndexKey(userId);
            Set<String> tokens = redis.opsForSet().members(indexKey);
            if (tokens != null) {
                for (String t : tokens) {
                    redis.delete(key(t));
                }
            }
            redis.delete(indexKey);
        } catch (Exception e) {
            log.warn("批量注销用户 {} 的令牌失败：{}", userId, e.toString());
        }
    }

    private String write(CurrentUser user) {
        return json.writeValueAsString(new Persisted(
                user.userId(), user.username(), user.nickname(), user.roles()));
    }

    private CurrentUser read(String payload) throws Exception {
        JsonNode node = json.readTree(payload);
        Set<String> roles = new LinkedHashSet<>();
        JsonNode rolesNode = node.path("roles");
        if (rolesNode.isArray()) {
            rolesNode.forEach(n -> {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) {
                    roles.add(v);
                }
            });
        }
        return new CurrentUser(
                node.path("userId").asLong(0),
                node.path("username").asText(null),
                node.path("nickname").asText(null),
                roles);
    }

    /** 落 Redis 的结构，与 CurrentUser 分开是为了让存储格式独立于业务记录演化。 */
    private record Persisted(Long userId, String username, String nickname, Set<String> roles) {
    }

    private static String key(String token) {
        return KEY_PREFIX + token;
    }

    private static String userIndexKey(Long userId) {
        return USER_INDEX_PREFIX + userId;
    }
}
