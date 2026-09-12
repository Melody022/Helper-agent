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

    /** 上传时的原始文件名，如 "GBT+25523-2022.pdf"；非上传来源（文章/飞轮）为 null */
    private String fileName;

    /** 原件落盘的相对路径（相对 rag.upload.dir）；非上传来源为 null */
    private String filePath;

    /**
     * PDF 预览件的相对路径。
     *
     * <p><b>只有 Office 文档才有。</b>PDF / 图片 / txt 的原件浏览器本来就能直接显示，
     * 不需要另存一份；只有 docx/xlsx/pptx 浏览器渲染不了（只会触发下载），才转一份 PDF。
     */
    private String previewPath;

    /** 原件字节数 */
    private Long fileSize;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
