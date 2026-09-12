package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库切片，对应 ai_knowledge_chunk。本表没有 del_flag（随文档一起删）。
 *
 * <p>向量<b>不存这里</b>——向量在 ES（见 {@code KnowledgeRetriever}），
 * 本表通过 {@code vectorId} 指向 ES 里的文档 id。MySQL 里存原文，
 * ES 里存"用来检索的副本"，职责分开。
 */
@Data
@TableName("ai_knowledge_chunk")
public class AiKnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    /** 父块 id（预留给 small-to-big 召回） */
    private Long parentId;

    private Integer chunkIndex;

    private String content;

    private String contentHash;

    private Integer tokenCount;

    private Integer charCount;

    /** 生成向量用的模型标识，换模型时据此判断哪些切片需要重算 */
    private String embeddingModel;

    /** ES 里的文档 id */
    private String vectorId;

    /**
     * 命中的表格 id（ai_knowledge_table.id），非表格块为 null。
     *
     * <p>带这个值的切片是某张表的<b>摘要</b>。检索命中它之后，要用完整表格替换掉
     * 摘要内容再喂给模型——只召回摘要等于只给模型看表头，答不对。
     */
    private Long tableId;

    private LocalDateTime createTime;
}
