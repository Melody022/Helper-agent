package com.jixiejia.agent.api;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 骨架自检端点：确认应用已起、jxj 库可连、ai_* 表已建。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("app", "jxj-ai-agent");
        result.put("status", "UP");

        try {
            Integer aiTables = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'ai\\_%'",
                    Integer.class);
            Integer bizTables = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                            + " AND table_name IN ('jxb_equipment','jxb_chuzu','jxb_qiuzu','jxb_xuqiu','jxb_xunjia','cms_article')",
                    Integer.class);

            result.put("database", "UP");
            result.put("aiTables", aiTables);
            result.put("bizTablesFound", bizTables + "/6");
        } catch (Exception e) {
            result.put("database", "DOWN");
            result.put("error", e.getMessage());
        }
        return result;
    }
}
