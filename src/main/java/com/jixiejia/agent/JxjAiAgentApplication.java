package com.jixiejia.agent;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.Environment;

import java.net.InetAddress;

/**
 * 机械家（jxj）二手工程机械平台 AI 客服/导购 Agent 启动类。
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.jixiejia.agent.persistence.mapper")
public class JxjAiAgentApplication {

    public static void main(String[] args) throws Exception {
        SpringApplication app = new SpringApplicationBuilder(JxjAiAgentApplication.class).build(args);
        Environment env = app.run(args).getEnvironment();

        String protocol = env.getProperty("server.ssl.key-store") != null ? "https" : "http";
        String host = InetAddress.getLocalHost().getHostAddress();
        String port = env.getProperty("server.port", "8082");

        log.info("\n"
                + "=========================================================\n"
                + "  机械家 AI Agent 启动成功！\n"
                + "---------------------------------------------------------\n"
                + "  本机访问:  {}://localhost:{}\n"
                + "  局域网访问: {}://{}:{}\n"
                + "  API 文档:  {}://localhost:{}/v3/api-docs\n"
                + "  健康检查:  {}://localhost:{}/api/health\n"
                + "=========================================================\n",
                protocol, port,
                protocol, host, port,
                protocol, port,
                protocol, port
        );
    }
}
