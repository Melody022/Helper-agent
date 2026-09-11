package com.jixiejia.agent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Elasticsearch 客户端装配。
 *
 * <p>手动建 Bean 而不是靠 Spring Boot 自动配置：一是 Boot 4 把自动配置拆得很散，
 * 依赖它的存在与否不如自己声明确定；二是我们要明确指定 JSON 映射器。
 *
 * <p>关于 {@link JacksonJsonpMapper}：它用的是 Jackson 2（{@code com.fasterxml.jackson}）。
 * 本项目业务代码统一用 Jackson 3（{@code tools.jackson}），这里的 Jackson 2
 * 是 ES 官方客户端自带的传递依赖，只在这一处使用，不要扩散到别的代码里。
 */
@Configuration
public class ElasticsearchConfig {

    /**
     * 底层 REST 客户端。由 Spring 负责关闭——{@code ElasticsearchClient} 本身没有无参
     * close 方法，直接把 destroyMethod 挂在它上面会报 "Invalid destruction signature"，
     * 所以要关就得关这一层。
     */
    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(
            @Value("${spring.elasticsearch.uris:http://localhost:9200}") String uris) {

        List<HttpHost> hosts = Arrays.stream(uris.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(HttpHost::create)
                .toList();

        return RestClient.builder(hosts.toArray(new HttpHost[0])).build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
