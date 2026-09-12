-- =============================================================================
-- jxj-ai-agent  M9：切片带上结构元数据（章节路径 + 页码）
--
-- 为什么需要：
--   原来的切片只有正文，**块脱离上下文**——"5.4.4.1 润滑系统应安全可靠"这种块，
--   模型不知道它挂在文档的哪一章底下；而且**无法溯源**，连"依据在第 10 页"都说不出来。
--
--   解析阶段现在已经能把正文拆成「页 × 章节」的段（见 DocBlock / SectionTracker），
--   这一步把这两个元数据落到切片上。
--
-- 幂等：用 information_schema 守卫，可重复执行。
-- =============================================================================

SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_chunk'
              AND COLUMN_NAME = 'section_path');
SET @ddl1 := IF(@c1 = 0,
  'ALTER TABLE `ai_knowledge_chunk`
     ADD COLUMN `section_path` varchar(500) DEFAULT NULL
       COMMENT ''章节路径，如 "5 安全要求 > 5.4 润滑系统"；识别不出时为文档标题''',
  'DO 0');
PREPARE s1 FROM @ddl1; EXECUTE s1; DEALLOCATE PREPARE s1;

SET @c2 := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_chunk'
              AND COLUMN_NAME = 'page_no');
SET @ddl2 := IF(@c2 = 0,
  'ALTER TABLE `ai_knowledge_chunk`
     ADD COLUMN `page_no` int DEFAULT NULL COMMENT ''所在页（1 起）；用于答案溯源''',
  'DO 0');
PREPARE s2 FROM @ddl2; EXECUTE s2; DEALLOCATE PREPARE s2;
