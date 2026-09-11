-- =============================================================================
-- jxj-ai-agent  M1 骨架：Agent 侧表结构（统一 ai_ 前缀，避开 RuoYi 的 sys_*）
-- 目标库：jxj（MySQL 8.0）
-- 约定：utf8mb4 / utf8mb4_general_ci / InnoDB（与同库 RuoYi 表保持一致）
--       id 为 bigint unsigned 自增；del_flag '0'=存在 '2'=删除（配合 MyBatis-Plus 逻辑删除）
--       不使用外键：业务表（ums_member 等）字符集/引擎不保证一致，用索引约束关系
-- 幂等：全部 CREATE TABLE IF NOT EXISTS；种子数据 INSERT IGNORE
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 账号 / 角色 / 工具白名单
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_user` (
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`        varchar(64)     NOT NULL                COMMENT '登录账号',
  `password`        varchar(100)    NOT NULL                COMMENT 'BCrypt 密码哈希',
  `nickname`        varchar(64)              DEFAULT NULL    COMMENT '昵称',
  `status`          char(1)         NOT NULL DEFAULT '0'    COMMENT '状态 0正常 1停用',
  `mobile`          varchar(20)              DEFAULT NULL    COMMENT '手机号',
  `email`           varchar(64)              DEFAULT NULL    COMMENT '邮箱',
  `last_login_time` datetime                 DEFAULT NULL    COMMENT '最后登录时间',
  `create_time`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`        char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  `remark`          varchar(500)             DEFAULT NULL    COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 侧账号（管理台 / 接口调用）';

CREATE TABLE IF NOT EXISTS `ai_role` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_key`    varchar(64)     NOT NULL                COMMENT '角色标识 ADMIN/USER',
  `role_name`   varchar(64)     NOT NULL                COMMENT '角色名称',
  `sort`        int             NOT NULL DEFAULT 0      COMMENT '显示顺序',
  `status`      char(1)         NOT NULL DEFAULT '0'    COMMENT '状态 0正常 1停用',
  `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`    char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  `remark`      varchar(500)             DEFAULT NULL   COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_role_key` (`role_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 侧角色';

CREATE TABLE IF NOT EXISTS `ai_user_role` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     bigint unsigned NOT NULL                COMMENT 'ai_user.id',
  `role_id`     bigint unsigned NOT NULL                COMMENT 'ai_role.id',
  `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_user_role` (`user_id`,`role_id`),
  KEY `idx_ai_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='账号-角色关联';

-- 工具注册表：与代码里 @Tool 方法一一对应，用于按角色做能力白名单
CREATE TABLE IF NOT EXISTS `ai_tool` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tool_name`   varchar(128)    NOT NULL                COMMENT '工具名（@Tool name，全局唯一）',
  `capability`  varchar(64)     NOT NULL                COMMENT '能力标识 equipment/chuzu/qiuzu/demand/news/policy',
  `display_name` varchar(128)            DEFAULT NULL   COMMENT '展示名',
  `description` varchar(1000)            DEFAULT NULL   COMMENT '工具描述（供模型理解）',
  `param_schema` json                    DEFAULT NULL   COMMENT '入参 JSON Schema（由 @Tool 反射生成）',
  `impl_bean`   varchar(255)             DEFAULT NULL   COMMENT '实现 Bean 名',
  `status`      char(1)         NOT NULL DEFAULT '0'    COMMENT '状态 0启用 1停用',
  `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`    char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  `remark`      varchar(500)             DEFAULT NULL   COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_tool_name` (`tool_name`),
  KEY `idx_ai_tool_capability` (`capability`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 工具注册表';

CREATE TABLE IF NOT EXISTS `ai_role_tool` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id`     bigint unsigned NOT NULL                COMMENT 'ai_role.id',
  `tool_id`     bigint unsigned NOT NULL                COMMENT 'ai_tool.id',
  `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_role_tool` (`role_id`,`tool_id`),
  KEY `idx_ai_role_tool_tool` (`tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色-工具白名单';

-- -----------------------------------------------------------------------------
-- 2. Agent 注册表 / Prompt 热更
-- -----------------------------------------------------------------------------
-- priority 数值越大越优先；is_fallback=1 为兜底 Agent（GeneralAgent）
CREATE TABLE IF NOT EXISTS `ai_agent` (
  `id`           bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_key`    varchar(64)     NOT NULL                COMMENT 'Agent 标识 EquipmentAgent 等',
  `agent_name`   varchar(128)    NOT NULL                COMMENT '展示名',
  `agent_bean`   varchar(255)             DEFAULT NULL   COMMENT 'Spring Bean 名',
  `description`  varchar(1000)            DEFAULT NULL   COMMENT '职责说明',
  `capabilities` json                     DEFAULT NULL   COMMENT '能力标识数组 ["chuzu","qiuzu"]',
  `priority`     int             NOT NULL DEFAULT 0      COMMENT '匹配优先级，越大越优先',
  `is_fallback`  tinyint         NOT NULL DEFAULT 0      COMMENT '是否兜底 0否 1是',
  `status`       char(1)         NOT NULL DEFAULT '0'    COMMENT '状态 0启用 1停用',
  `create_time`  datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`     char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  `remark`       varchar(500)             DEFAULT NULL   COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_agent_key` (`agent_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI Agent 注册表';

CREATE TABLE IF NOT EXISTS `ai_prompt` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `prompt_key`  varchar(128)    NOT NULL                COMMENT 'Prompt 标识（同一 key 多版本）',
  `name`        varchar(128)             DEFAULT NULL   COMMENT '名称',
  `content`     mediumtext      NOT NULL                COMMENT 'Prompt 正文',
  `version`     int             NOT NULL DEFAULT 1      COMMENT '版本号',
  `model_key`   varchar(64)              DEFAULT NULL   COMMENT '目标模型 mimo/small',
  `temperature` decimal(3,2)             DEFAULT NULL   COMMENT '温度',
  `status`      char(1)         NOT NULL DEFAULT '0'    COMMENT '状态 0启用 1停用',
  `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`    char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  `remark`      varchar(500)             DEFAULT NULL   COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_prompt_key_version` (`prompt_key`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Prompt 模板（存库热更）';

-- -----------------------------------------------------------------------------
-- 3. 发布（出租/求租）工作流
-- -----------------------------------------------------------------------------
-- AI 会话身份 ↔ 平台真实会员的映射；落库时 customer_id 取 member_id
CREATE TABLE IF NOT EXISTS `ai_publish_mapping` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     bigint unsigned NOT NULL                COMMENT 'ai_user.id',
  `member_id`   bigint          NOT NULL                COMMENT 'ums_member.id',
  `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_publish_mapping_user` (`user_id`),
  KEY `idx_ai_publish_mapping_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 账号与平台会员映射';

-- 发布待审：AI 收集表单 → 确认令牌 → 落 jxb_chuzu/jxb_qiuzu（audit_status='0' 待审核）
CREATE TABLE IF NOT EXISTS `ai_publish_request` (
  `id`                 bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `request_no`         varchar(64)     NOT NULL                COMMENT '业务流水号',
  `conversation_id`    varchar(64)              DEFAULT NULL   COMMENT '来源会话',
  `user_id`            bigint unsigned NOT NULL                COMMENT 'ai_user.id',
  `member_id`          bigint                   DEFAULT NULL   COMMENT 'ums_member.id，即落库 customer_id',
  `target_table`       varchar(32)     NOT NULL                COMMENT '目标表 jxb_chuzu/jxb_qiuzu',
  `payload`            json            NOT NULL                COMMENT '表单数据（字段与目标表对齐）',
  `status`             varchar(20)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED/SUBMITTED/REJECTED/EXPIRED',
  `confirm_token`      varchar(64)              DEFAULT NULL   COMMENT '确认令牌',
  `token_expire_time`  datetime                 DEFAULT NULL   COMMENT '令牌过期时间',
  `biz_table`          varchar(32)              DEFAULT NULL   COMMENT '落库目标表',
  `biz_id`             bigint unsigned          DEFAULT NULL   COMMENT '落库后业务主键',
  `reviewer_id`        bigint unsigned          DEFAULT NULL   COMMENT '审核人 ai_user.id',
  `review_note`        varchar(500)             DEFAULT NULL   COMMENT '审核意见',
  `review_time`        datetime                 DEFAULT NULL   COMMENT '审核时间',
  `create_time`        datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`        datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`           char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_publish_request_no` (`request_no`),
  KEY `idx_ai_publish_request_status` (`status`),
  KEY `idx_ai_publish_request_user` (`user_id`),
  KEY `idx_ai_publish_request_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='发布请求（待确认/待审核）';

-- -----------------------------------------------------------------------------
-- 4. 知识库（结构化切片；向量落 ES，这里存权威原文）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_knowledge_doc` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `doc_type`      varchar(32)     NOT NULL                COMMENT '来源类型 article/upload/faq',
  `source_id`     varchar(64)              DEFAULT NULL   COMMENT '外部来源主键，如 cms_article.id',
  `title`         varchar(500)             DEFAULT NULL   COMMENT '标题',
  `category`      varchar(128)             DEFAULT NULL   COMMENT '分类/章节',
  `content`       longtext                 DEFAULT NULL   COMMENT '解析后全文',
  `content_hash`  char(64)                 DEFAULT NULL   COMMENT '内容指纹（入库查重）',
  `status`        varchar(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PARSED/CHUNKED/INDEXED/FAILED',
  `chunk_count`   int             NOT NULL DEFAULT 0      COMMENT '切片数',
  `error_msg`     varchar(1000)            DEFAULT NULL   COMMENT '失败原因',
  `create_time`   datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`      char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_kn_doc_type_source` (`doc_type`,`source_id`),
  KEY `idx_ai_kn_doc_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库文档';

CREATE TABLE IF NOT EXISTS `ai_knowledge_chunk` (
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `doc_id`          bigint unsigned NOT NULL                COMMENT 'ai_knowledge_doc.id',
  `parent_id`       bigint unsigned          DEFAULT NULL   COMMENT '父块 id（small-to-big 召回）',
  `chunk_index`     int             NOT NULL DEFAULT 0      COMMENT '块序号',
  `content`         text            NOT NULL                COMMENT '块正文',
  `content_hash`    char(64)                 DEFAULT NULL   COMMENT '内容指纹',
  `token_count`     int                      DEFAULT NULL   COMMENT 'token 数',
  `char_count`      int                      DEFAULT NULL   COMMENT '字符数',
  `embedding_model` varchar(64)              DEFAULT NULL   COMMENT '向量模型标识',
  `vector_id`       varchar(128)             DEFAULT NULL   COMMENT 'ES 文档 id',
  `create_time`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_kn_chunk_doc` (`doc_id`,`chunk_index`),
  KEY `idx_ai_kn_chunk_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库切片';

-- -----------------------------------------------------------------------------
-- 5. 飞轮（三入口 → 查重 → 人工审核 → 补知识库）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_flywheel_candidate` (
  `id`                bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `source`            varchar(32)     NOT NULL                COMMENT '入口 LOW_CONFIDENCE/SELF_EVAL_FAIL/USER_UNRESOLVED',
  `conversation_id`   varchar(64)              DEFAULT NULL   COMMENT '来源会话',
  `message_id`        bigint unsigned          DEFAULT NULL   COMMENT '来源消息',
  `question`          varchar(2000)            DEFAULT NULL   COMMENT '原始问题',
  `standard_question` varchar(2000)            DEFAULT NULL   COMMENT '标准化后问题',
  `question_hash`     char(64)                 DEFAULT NULL   COMMENT '问题指纹（查重）',
  `answer`            text                     DEFAULT NULL   COMMENT '当时的回答',
  `score`             decimal(5,4)             DEFAULT NULL   COMMENT '置信度/自评分/检索分',
  `status`            varchar(20)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/MERGED',
  `reviewer_id`       bigint unsigned          DEFAULT NULL   COMMENT '审核人 ai_user.id',
  `review_note`       varchar(500)             DEFAULT NULL   COMMENT '审核意见',
  `review_time`       datetime                 DEFAULT NULL   COMMENT '审核时间',
  `create_time`       datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`          char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_fw_status` (`status`),
  KEY `idx_ai_fw_hash` (`question_hash`),
  KEY `idx_ai_fw_source` (`source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='数据飞轮待审候选';

-- -----------------------------------------------------------------------------
-- 6. 会话 / 消息 / 审计 / 转人工
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_conversation` (
  `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id`  varchar(64)     NOT NULL                COMMENT '会话标识（LangGraph4j threadId）',
  `user_id`          bigint unsigned          DEFAULT NULL   COMMENT 'ai_user.id',
  `member_id`        bigint                   DEFAULT NULL   COMMENT 'ums_member.id',
  `title`            varchar(255)             DEFAULT NULL   COMMENT '会话标题',
  `last_agent_key`   varchar(64)              DEFAULT NULL   COMMENT '最近命中 Agent（会话粘性）',
  `last_intent`      varchar(64)              DEFAULT NULL   COMMENT '最近意图',
  `status`           varchar(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CLOSED/HANDOFF',
  `message_count`    int             NOT NULL DEFAULT 0      COMMENT '消息数',
  `last_active_time` datetime                 DEFAULT NULL   COMMENT '最后活跃时间',
  `create_time`      datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`         char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_conv_id` (`conversation_id`),
  KEY `idx_ai_conv_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 会话';

CREATE TABLE IF NOT EXISTS `ai_message` (
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` varchar(64)     NOT NULL                COMMENT '会话标识',
  `role`            varchar(16)     NOT NULL                COMMENT 'user/assistant/system/tool',
  `content`         mediumtext                 DEFAULT NULL COMMENT '正文',
  `intent`          varchar(64)              DEFAULT NULL   COMMENT '识别意图',
  `confidence`      decimal(5,4)             DEFAULT NULL   COMMENT '意图置信度',
  `agent_key`       varchar(64)              DEFAULT NULL   COMMENT '处理 Agent',
  `tool_calls`      json                     DEFAULT NULL   COMMENT '工具调用记录',
  `token_input`     int                      DEFAULT NULL   COMMENT '输入 token',
  `token_output`    int                      DEFAULT NULL   COMMENT '输出 token',
  `latency_ms`      int                      DEFAULT NULL   COMMENT '耗时毫秒',
  `create_time`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_msg_conv` (`conversation_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 消息';

-- 路由链审计：记录命中第几步、意图、Agent、工具、证据分
CREATE TABLE IF NOT EXISTS `ai_audit_log` (
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` varchar(64)              DEFAULT NULL   COMMENT '会话标识',
  `user_id`         bigint unsigned          DEFAULT NULL   COMMENT 'ai_user.id',
  `route_stage`     varchar(32)              DEFAULT NULL   COMMENT '命中的路由步骤',
  `intent`          varchar(64)              DEFAULT NULL   COMMENT '意图',
  `confidence`      decimal(5,4)             DEFAULT NULL   COMMENT '置信度',
  `classify_layer`  varchar(16)              DEFAULT NULL   COMMENT '分类层 keyword/small/llm',
  `agent_key`       varchar(64)              DEFAULT NULL   COMMENT '执行 Agent',
  `tool_names`      varchar(1000)            DEFAULT NULL   COMMENT '调用的工具，逗号分隔',
  `evidence_score`  decimal(5,4)             DEFAULT NULL   COMMENT '证据闸得分',
  `evidence_count`  int                      DEFAULT NULL   COMMENT '有效证据数',
  `decision`        json                     DEFAULT NULL   COMMENT '分流决策明细',
  `latency_ms`      int                      DEFAULT NULL   COMMENT '总耗时毫秒',
  `success`         tinyint         NOT NULL DEFAULT 1      COMMENT '是否成功 0否 1是',
  `error_msg`       varchar(1000)            DEFAULT NULL   COMMENT '错误信息',
  `create_time`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_audit_conv` (`conversation_id`),
  KEY `idx_ai_audit_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='路由链审计日志';

CREATE TABLE IF NOT EXISTS `ai_handoff` (
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` varchar(64)     NOT NULL                COMMENT '会话标识',
  `user_id`         bigint unsigned          DEFAULT NULL   COMMENT 'ai_user.id',
  `member_id`       bigint                   DEFAULT NULL   COMMENT 'ums_member.id',
  `reason`          varchar(500)             DEFAULT NULL   COMMENT '转人工原因',
  `contact`         varchar(100)             DEFAULT NULL   COMMENT '联系方式',
  `summary`         text                     DEFAULT NULL   COMMENT '会话摘要（交接待办）',
  `status`          varchar(20)     NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/PROCESSING/DONE/CANCELLED',
  `assignee`        varchar(64)              DEFAULT NULL   COMMENT '处理人',
  `queued_time`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入队时间',
  `handle_time`     datetime                 DEFAULT NULL   COMMENT '处理时间',
  `create_time`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`        char(1)         NOT NULL DEFAULT '0'    COMMENT '删除标志 0存在 2删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_handoff_status` (`status`),
  KEY `idx_ai_handoff_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='转人工队列';

-- -----------------------------------------------------------------------------
-- 7. 种子数据（幂等）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `ai_role` (`role_key`, `role_name`, `sort`, `remark`) VALUES
  ('ADMIN', '管理员', 1, '管理台全部权限'),
  ('USER',  '普通用户', 2, '仅对话与查询能力');

INSERT IGNORE INTO `ai_agent` (`agent_key`, `agent_name`, `agent_bean`, `description`, `capabilities`, `priority`, `is_fallback`) VALUES
  ('EquipmentAgent', '设备查询助手', 'equipmentAgent', '找设备 / 详情 / 推荐',   JSON_ARRAY('equipment'),              100, 0),
  ('RentalAgent',    '租赁助手',     'rentalAgent',    '出租 / 求租 / 需求询价', JSON_ARRAY('chuzu','qiuzu','demand'),  90, 0),
  ('KnowledgeAgent', '知识助手',     'knowledgeAgent', '平台规则 / FAQ / 资讯',  JSON_ARRAY('policy','news'),           80, 0),
  ('PublishAgent',   '发布助手',     'publishAgent',   '发布出租 / 求租',        JSON_ARRAY('publish'),                 70, 0),
  ('GeneralAgent',   '通用助手',     'generalAgent',   '问候 / 帮助 / 兜底',     JSON_ARRAY(),                          0, 1);
