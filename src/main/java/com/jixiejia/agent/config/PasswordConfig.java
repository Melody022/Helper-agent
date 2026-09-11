package com.jixiejia.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码编码器。
 *
 * <p>刻意单独成一个配置类，不放在 {@link WebConfig} 里：WebConfig 依赖 AuthInterceptor，
 * AuthInterceptor 依赖鉴权服务，而鉴权服务又需要密码编码器——把编码器放在 WebConfig
 * 会连成一条 AuthService → WebConfig → AuthInterceptor → AuthService 的环，
 * 启动直接失败。让编码器独立，环就断了。
 *
 * <p>只引 spring-security-crypto 这一个包，不引 Spring Security 全家桶。
 * 强度取默认的 10：登录是低频操作，不值得为省几十毫秒降低强度。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
