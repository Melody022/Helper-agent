-- =============================================================================
-- jxj-ai-agent  LangGraph4j 状态图的检查点存储
--
-- 为什么需要：
--   发布确认要改成「human-in-the-loop」——图跑到"等用户确认"就挂起，
--   用户下一轮回复后从挂起点恢复。**挂起期间的状态必须有地方存**，
--   否则图无从恢复（这是 interrupt 这个能力的前提，不是可选项）。
--
-- 为什么不用库里的 MemorySaver：
--   PublishFormService 的类注释已经写明「状态放内存里，进程一重启就没了，
--   用户会莫名其妙地"从头再来"」。用 MemorySaver 等于打自己的脸。
--   库里自带的另一个实现是 FileSystemSaver，但它把状态写在本地磁盘上——
--   这个项目已经有一个落盘目录了（知识库原件），再混一份图状态进去不好排查。
--   库里没有 MySQL 实现，所以这里自己实现一个（AbstractCheckpointSaver 只有 4 个方法）。
--
-- 存的是什么：**图跑到哪了**（node_id / next_node_id）+ 那一轮的图状态。
-- 和业务表的分工是清楚的：
--   - 这张表管"流程位置"，可以随时清掉，不影响业务数据；
--   - ai_publish_request 管"用户填了什么"，是后台审核的依据，不能丢。
--
-- 幂等：用 information_schema 守卫，可重复执行。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_graph_checkpoint (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    thread_id     VARCHAR(128)    NOT NULL COMMENT '会话线程 id（本项目用 conversationId）',
    checkpoint_id VARCHAR(64)     NOT NULL COMMENT '检查点 id，同一线程内递增',
    node_id       VARCHAR(64)              DEFAULT NULL COMMENT '产出这个检查点的节点',
    next_node_id  VARCHAR(64)              DEFAULT NULL COMMENT '下一步该跑哪个节点；有值说明图挂起在这里',
    state_data    LONGTEXT                 DEFAULT NULL COMMENT 'Base64 的图状态（用图的 StateSerializer 序列化）',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_thread_checkpoint (thread_id, checkpoint_id),
    KEY idx_thread_id (thread_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='LangGraph4j 状态图检查点';
