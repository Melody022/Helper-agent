package com.jixiejia.agent.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件。分页依赖 JSqlParser 解析 SQL，故 pom 额外引入了 mybatis-plus-jsqlparser。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * jxj 业务表只读护栏。MyBatis Spring Boot 会自动收集容器里所有 {@link org.apache.ibatis.plugin.Interceptor} Bean。
     */
    @Bean
    public BusinessReadOnlyInterceptor businessReadOnlyInterceptor() {
        return new BusinessReadOnlyInterceptor();
    }
}
