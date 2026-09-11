package com.jixiejia.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI jxjAiAgentOpenApi() {
        return new OpenAPI().info(new Info()
                .title("机械家 AI Agent API")
                .description("二手工程机械平台 AI 客服/导购 Agent：找设备、出租求租、需求询价、行业资讯、发布。")
                .version("0.0.1-SNAPSHOT"));
    }
}
