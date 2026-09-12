-- =============================================================================
-- jxj-ai-agent  引用角标 + 原件回溯：把上传的原件位置记下来
--
-- 为什么需要：
--   上一轮（V3）让切片带上了页码和章节路径，答案能说清"依据来自国标第 10 页"，
--   但**原件根本没存**——上传时读进内存、解析完就丢，所以那句话点不下去。
--   这一版把原件的位置记下来，"点角标 → 跳到原件那一页"才有可能。
--
-- preview_path 只有 Office 文档才有：
--   PDF / 图片 / txt 的原件浏览器本来就能直接显示，不需要另存一份；
--   只有 docx/xlsx/pptx 浏览器渲染不了（只会触发下载），才转一份 PDF。
--
-- 幂等：用 information_schema 守卫，可重复执行。
-- =============================================================================

SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_doc'
              AND COLUMN_NAME = 'file_name');
SET @ddl1 := IF(@c1 = 0,
  'ALTER TABLE `ai_knowledge_doc`
     ADD COLUMN `file_name` varchar(255) DEFAULT NULL COMMENT ''上传时的原始文件名''',
  'DO 0');
PREPARE s1 FROM @ddl1; EXECUTE s1; DEALLOCATE PREPARE s1;

SET @c2 := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_doc'
              AND COLUMN_NAME = 'file_path');
SET @ddl2 := IF(@c2 = 0,
  'ALTER TABLE `ai_knowledge_doc`
     ADD COLUMN `file_path` varchar(500) DEFAULT NULL
       COMMENT ''原件落盘的相对路径（相对 rag.upload.dir）''',
  'DO 0');
PREPARE s2 FROM @ddl2; EXECUTE s2; DEALLOCATE PREPARE s2;

SET @c3 := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_doc'
              AND COLUMN_NAME = 'preview_path');
SET @ddl3 := IF(@c3 = 0,
  'ALTER TABLE `ai_knowledge_doc`
     ADD COLUMN `preview_path` varchar(500) DEFAULT NULL
       COMMENT ''PDF 预览件相对路径；仅 Office 文档有，其余原样返回原件''',
  'DO 0');
PREPARE s3 FROM @ddl3; EXECUTE s3; DEALLOCATE PREPARE s3;

SET @c4 := (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_doc'
              AND COLUMN_NAME = 'file_size');
SET @ddl4 := IF(@c4 = 0,
  'ALTER TABLE `ai_knowledge_doc`
     ADD COLUMN `file_size` bigint DEFAULT NULL COMMENT ''原件字节数''',
  'DO 0');
PREPARE s4 FROM @ddl4; EXECUTE s4; DEALLOCATE PREPARE s4;
