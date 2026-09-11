package com.jixiejia.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词库：把提示词从 Java 代码里挪到 {@code classpath:prompts/*.md}。
 *
 * <p><b>为什么不放数据库。</b>数据库方案听起来更灵活（运营改完即时生效），
 * 但代价是这个项目承受不起的：改提示词不再经过代码评审、不能 git revert、
 * 本地和生产的提示词会悄悄不一致。而提示词里有几条是**防幻觉的护栏**——
 * 比如"平台规则类问题不许凭常识回答"——护栏被随手改掉，系统就开始编规则，
 * 而且没有任何告警。
 *
 * <p>放成文件之后，改提示词仍然是代码变更：能 review、能回滚、diff 干净、
 * 非 Java 同学也能直接读改。代价是改完要重新发布，对提示词这种低频变更完全可以接受。
 *
 * <p><b>找不到文件会怎样。</b>启动时加载一次，缺文件只记警告不阻断启动；
 * 运行时取不到时返回一段保守的兜底话术（"我不知道，请转人工"），
 * 而不是抛异常或返回空串——空提示词会让模型完全放飞。
 */
@Slf4j
@Component
public class PromptLibrary {

    private static final String LOCATION_PATTERN = "classpath*:prompts/*.md";

    /** 取不到提示词时的兜底：宁可保守地说不知道，也不要让模型无约束地自由发挥 */
    private static final String FALLBACK = """
            你是「机械家」二手工程机械平台的客服助手。
            你暂时无法确定用户的问题，请如实说明并建议用户回复"转人工"联系客服，
            不要凭印象编造设备信息、价格或平台规则。
            """;

    /** key（文件名去掉 .md）→ 提示词正文 */
    private final Map<String, String> prompts = new LinkedHashMap<>();

    public PromptLibrary() {
        load();
    }

    /** 启动时从 classpath 一次性载入。 */
    private void load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(LOCATION_PATTERN);

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) {
                    continue;
                }
                String key = filename.substring(0, filename.length() - 3);
                prompts.put(key, read(resource));
            }
            log.info("提示词载入完成：{} 条 {}", prompts.size(), prompts.keySet());

        } catch (Exception e) {
            log.warn("提示词载入失败，将全部使用兜底话术：{}", e.toString());
        }
    }

    /**
     * 取提示词。
     *
     * @param key 文件名（不含 .md），如 {@code equipment-agent}
     * @return 提示词正文；缺失时返回保守的兜底话术
     */
    public String get(String key) {
        String prompt = prompts.get(key);
        if (prompt == null || prompt.isBlank()) {
            log.warn("提示词 {} 不存在，使用兜底话术。检查 classpath:prompts/{}.md", key, key);
            return FALLBACK;
        }
        return prompt;
    }

    /** 取提示词并替换模板变量。变量写成 {@code {{name}}}。 */
    public String get(String key, Map<String, String> variables) {
        String template = get(key);
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> e : variables.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return result;
    }

    /** 已载入的提示词 key，供排查与管理台展示。 */
    public java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(prompts.keySet());
    }

    /** 某个 key 是否有对应的提示词文件。 */
    public boolean has(String key) {
        String prompt = prompts.get(key);
        return prompt != null && !prompt.isBlank();
    }

    private static String read(Resource resource) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    sb.append('\n');
                }
                sb.append(line);
                first = false;
            }
        }
        // 文件末尾的换行会被 readLine 吃掉，这里不加回来，模型不依赖它
        return sb.toString();
    }
}
