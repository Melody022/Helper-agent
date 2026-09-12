package com.jixiejia.agent.persistence.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库表格，对应 ai_knowledge_table。
 *
 * <p><b>为什么表格要单独存、不跟着正文一起切片。</b>跨页大表被按页切开后，
 * 上一页的表尾和下一页的表头会落到两个切片里，向量库存的是两份残缺片段——
 * 检索出一半，模型拿到缺失表头的行，答出来的东西是错的。
 *
 * <p>所以这里的做法是：表格本体（{@link #markdown}）<b>不参与切片</b>，原样存在这张表里；
 * 只把"表头 + 标题 + 前几行"生成一段<b>摘要</b>去向量化，摘要切片上带 {@code tableId}。
 * 检索命中摘要后，再按 id 把完整表格取回来喂给模型——<b>不能只召回片段</b>。
 */
@Data
@TableName("ai_knowledge_table")
public class AiKnowledgeTable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档 ai_knowledge_doc.id */
    private Long docId;

    /** 文档内第几张表，从 0 开始 */
    private Integer tableIndex;

    /** 起始页 */
    private Integer pageFrom;

    /** 结束页；跨页合并过的表这里会大于 pageFrom */
    private Integer pageTo;

    /** 表格标题/上下文说明（取表格上方最近的一行文字） */
    private String caption;

    /** 表头，用 | 分隔。用来生成摘要 */
    private String headers;

    /** 完整 markdown 表格。检索命中摘要后原样喂给模型 */
    private String markdown;

    /** 数据行数（不含表头） */
    private Integer rowCount;

    /** 列数 */
    private Integer colCount;

    /** 内容指纹，同表重复上传时判重 */
    private String contentHash;

    private LocalDateTime createTime;
}
