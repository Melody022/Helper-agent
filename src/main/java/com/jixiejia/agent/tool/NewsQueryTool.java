package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jixiejia.agent.persistence.entity.jxj.CmsArticle;
import com.jixiejia.agent.persistence.mapper.jxj.CmsArticleMapper;
import com.jixiejia.agent.persistence.support.BizFilters;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资讯查询工具（能力标识 news），供 KnowledgeAgent 使用。
 *
 * <p>底层是 cms_article。正文 {@code desc} 列存的是富文本 HTML，
 * 必须剥标签再交给模型（见 {@link ToolSupport#stripHtml}），否则一大半 token 花在标签上。
 *
 * <p>与 M6/M8 的 RAG 知识线是两条路：这里是按标题/正文关键词直查数据库，
 * 适合"有哪些 XX 资讯"这类明确检索；"平台规则怎么规定"这类需要语义理解的问题走 RAG + 证据闸。
 */
@Component
@RequiredArgsConstructor
public class NewsQueryTool implements BizTool {

    @Override
    public String capability() {
        return ToolCapability.NEWS;
    }

    @Override
    public String displayName() {
        return "资讯查询";
    }

    /** 列表里摘要的截断长度 */
    private static final int SUMMARY_CHARS = 150;

    /** 详情正文的截断长度 */
    private static final int DETAIL_CHARS = 1500;

    private final CmsArticleMapper articleMapper;
    private final ToolSupport support;

    public record NewsItem(
            Long id,
            String title,
            String author,
            LocalDateTime publishTime,
            Integer readCount,
            String summary
    ) {
    }

    public record NewsDetail(
            Long id,
            String title,
            String author,
            LocalDateTime publishTime,
            Integer readCount,
            String content
    ) {
    }

    @Tool(name = "search_news", description = """
            按关键词检索平台发布的行业资讯、评测、保养维修等文章。
            当用户问"有什么XX的资讯""最近有什么新闻""XX相关的文章"时调用。
            返回标题和摘要；用户想读全文时再用 get_news_detail 取正文。""")
    public String searchNews(
            @ToolParam(description = "检索关键词，如 挖掘机、保养、二手、评测", required = false) String keyword,
            @ToolParam(description = "返回条数，默认5，最大20", required = false) Integer limit) {

        int size = support.capLimit(limit);
        LambdaQueryWrapper<CmsArticle> w = BizFilters.visibleArticle();

        String kw = support.blankToNull(keyword);
        if (kw != null) {
            w.and(inner -> inner.like(CmsArticle::getTitle, kw)
                    .or().like(CmsArticle::getDesc, kw));
        }

        w.orderByDesc(CmsArticle::getCreateTime).orderByDesc(CmsArticle::getId).last("limit " + size);
        List<CmsArticle> rows = articleMapper.selectList(w);

        List<NewsItem> items = rows.stream().map(a -> new NewsItem(
                a.getId(),
                a.getTitle(),
                a.getAuthor(),
                a.getCreateTime(),
                a.getReadNum(),
                ToolSupport.truncate(ToolSupport.stripHtml(a.getDesc()), SUMMARY_CHARS))).toList();

        String note = items.isEmpty() ? support.emptyNote("没有匹配的资讯") : null;
        return support.json(new ToolSupport.ListResult<>(items.size(), items, note));
    }

    @Tool(name = "get_news_detail", description = """
            按文章 id 获取资讯正文。
            在 search_news 拿到 id 后，用户想进一步了解某篇文章时调用。""")
    public String getNewsDetail(
            @ToolParam(description = "文章 id，取自 search_news 返回的 id") Long articleId) {

        if (articleId == null) {
            return support.json(new ToolSupport.ItemResult<>(null, "缺少文章 id"));
        }

        CmsArticle a = articleMapper.selectOne(
                BizFilters.visibleArticle().eq(CmsArticle::getId, articleId));

        if (a == null) {
            return support.json(new ToolSupport.ItemResult<>(null,
                    "文章不存在或未发布（id=" + articleId + "）"));
        }

        NewsDetail detail = new NewsDetail(
                a.getId(),
                a.getTitle(),
                a.getAuthor(),
                a.getCreateTime(),
                a.getReadNum(),
                ToolSupport.truncate(ToolSupport.stripHtml(a.getDesc()), DETAIL_CHARS));

        return support.json(new ToolSupport.ItemResult<>(detail, null));
    }
}
