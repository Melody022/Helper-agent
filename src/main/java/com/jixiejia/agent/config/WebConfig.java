package com.jixiejia.agent.config;

import com.jixiejia.agent.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配：注册登录拦截器。
 *
 * <p>密码编码器不在这里，见 {@link PasswordConfig}——放一起会形成循环依赖。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 挂在 /api/** 上，默认要求登录；公开路径由 AuthInterceptor 内部的名单放行
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }
}
