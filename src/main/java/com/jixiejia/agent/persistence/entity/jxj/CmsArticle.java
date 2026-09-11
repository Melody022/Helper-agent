package com.jixiejia.agent.persistence.entity.jxj;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资讯文章，对应 cms_article。只读，是知识库（M8 RAG）的主要来源之一。
 */
@Data
@TableName("cms_article")
public class CmsArticle {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String author;

    /** 创建者 ums_member.id */
    private Long customerId;

    /** 所属栏目 id */
    private Long columnId;

    private Integer sort;

    /** 是否发布 0 发布 1 不发布 */
    private String isRelease;

    private String seoKeywords;

    private String seoDesc;

    private String image;

    /**
     * 文章正文（HTML）。desc 是 MySQL 保留字，必须反引号转义，
     * 否则 MyBatis-Plus 生成的 SELECT 会语法报错。
     */
    @TableField("`desc`")
    private String desc;

    private Integer readNum;

    /** 删除标记 0 未删除 1 删除 */
    private String delFlag;

    private LocalDateTime createTime;

    private LocalDateTime modifyTime;

    private LocalDateTime delTime;
}
