package com.jixiejia.agent.persistence.mapper.jxj;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jixiejia.agent.persistence.entity.jxj.CmsArticle;

/**
 * 资讯文章，对应 jxj 库 cms_article 表，只读。
 *
 * <p>本包下的 Mapper 受 {@link com.jixiejia.agent.config.BusinessReadOnlyInterceptor} 保护，
 * 任何写入都会直接抛异常。可见性过滤请统一走
 * {@link com.jixiejia.agent.persistence.support.BizFilters}，不要散落到各处。
 */
public interface CmsArticleMapper extends BaseMapper<CmsArticle> {
}
