package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档，对应 ai_knowledge_doc。
 *
 * <p>这里存的是<b>权威原文</b>；用于检索的切片与向量在 ai_knowledge_chunk 和 ES 里。
 * 之所以两边都存：ES 可能重建（换分词器、换索引结构），重建时要能只靠数据库重新灌一遍，
 * 不能把原文只放在搜索引擎里。
 */
@Data
@TableName("ai_knowledge_doc")
public class AiKnowledgeDoc {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源类型 article（cms_article）/ upload（上传文档）/ faq（飞轮补进来的问答） */
    private String docType;

    /** 外部来源主键，如 cms_article.id */
    private String sourceId;

    private String title;

    private String category;

    /** 解析后的全文（已剥 HTML） */
    private String content;

    /** 内容指纹，用于入库查重 */
    private String contentHash;

    /** PENDING / PARSED / CHUNKED / INDEXED / FAILED */
    private String status;

    private Integer chunkCount;

    private String errorMsg;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
