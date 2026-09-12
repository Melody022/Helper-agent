-- =============================================================================
-- jxj-ai-agent  M9：知识库表格结构化（RAG 跨页复杂表格处理 · 方案 A）
-- 目标库：jxj（MySQL 8.0）
--
-- 为什么需要这张表：
--   跨页大表如果直接跟着正文一起切片向量化，会被切散——上一页的表尾和下一页的
--   表头分离，检索只能召回"半张表"，行列关系丢失，模型拿到的数据是错的。
--   所以表格本体**不参与切片**，按结构化形式单独存下来；只把"表头 + 摘要"做向量化，
--   命中摘要后再按 table_id 取回完整表格喂给模型。
--
-- 幂等：CREATE TABLE IF NOT EXISTS；加列用 information_schema 守卫，可重复执行。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 知识库表格：一份文档里的每张表存一行，跨页的已在解析阶段合并
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_knowledge_table` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `doc_id`        bigint unsigned NOT NULL                COMMENT 'ai_knowledge_doc.id',
  `table_index`   int             NOT NULL DEFAULT 0      COMMENT '文档内第几张表（从 0 开始）',
  `page_from`     int                      DEFAULT NULL   COMMENT '起始页',
  `page_to`       int                      DEFAULT NULL   COMMENT '结束页；跨页表时 > page_from',
  `caption`       varchar(500)             DEFAULT NULL   COMMENT '表格标题/上下文说明',
  `headers`       varchar(2000)            DEFAULT NULL   COMMENT '表头（| 分隔），用于生成摘要',
  `markdown`      longtext                 DEFAULT NULL   COMMENT '完整 markdown 表格；检索命中后原样喂给模型',
  `row_count`     int             NOT NULL DEFAULT 0      COMMENT '数据行数（不含表头）',
  `col_count`     int             NOT NULL DEFAULT 0      COMMENT '列数',
  `content_hash`  char(64)                 DEFAULT NULL   COMMENT '内容指纹（同表重复上传时判重）',
  `create_time`   datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_kn_table_doc_idx` (`doc_id`,`table_index`),
  KEY `idx_ai_kn_table_doc` (`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库表格（跨页已合并，本体不切片）';

-- -----------------------------------------------------------------------------
-- 切片关联表格：这一块是某张表的"摘要"，命中的是它，取回来的是整张表
-- -----------------------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME   = 'ai_knowledge_chunk'
    AND COLUMN_NAME  = 'table_id'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `ai_knowledge_chunk`
     ADD COLUMN `table_id` bigint unsigned DEFAULT NULL COMMENT ''命中的表格 id（方案A：该块是表格摘要，取回时用完整表替换）'',
     ADD KEY `idx_ai_kn_chunk_table` (`table_id`)',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
