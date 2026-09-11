package com.jixiejia.agent.persistence.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.jxj.CmsArticle;
import com.jixiejia.agent.persistence.entity.jxj.JxbChuzu;
import com.jixiejia.agent.persistence.entity.jxj.JxbEquipment;
import com.jixiejia.agent.persistence.entity.jxj.JxbQiuzu;
import com.jixiejia.agent.persistence.entity.jxj.JxbXunjia;
import com.jixiejia.agent.persistence.entity.jxj.JxbXuqiu;

/**
 * jxj 业务表的"对用户可见"口径，全部集中在这里。
 *
 * <p>这些条件是照抄 RuoYi 原系统的列表查询（如 EquipmentMapper.querySpus 的
 * {@code where shelves_status = 1}）定下来的，不是随便加的。散落到各个工具方法里迟早会走样，
 * 所以统一收口成本类，工具层只在此基础上追加业务条件。
 *
 * <p>关于 del_flag：各表口径不一致，必须区别对待。
 * <ul>
 *   <li>jxb_equipment.del_flag 是 int，0 未删 1 已删，历史行可能为 NULL；</li>
 *   <li>jxb_chuzu / jxb_qiuzu 等活动行的 del_flag 是空串或 NULL，只有被删的行才是 '1'。
 *       所以判据写成"不等于 '1'"，而不是"等于 '0'"——后者会把全部在租记录过滤光。</li>
 * </ul>
 */
public final class BizFilters {

    /** jxb_equipment.shelves_status 上架 */
    public static final String EQUIPMENT_ON_SHELF = "1";

    /** audit_thress 字典中"通过"的码值 */
    public static final String AUDIT_APPROVED = "1";

    /** cms_article.is_release 已发布 */
    public static final String ARTICLE_RELEASED = "0";

    private BizFilters() {
    }

    /** 设备：上架且未删除。 */
    public static LambdaQueryWrapper<JxbEquipment> visibleEquipment() {
        return Wrappers.<JxbEquipment>lambdaQuery()
                .eq(JxbEquipment::getShelvesStatus, EQUIPMENT_ON_SHELF)
                .and(w -> w.eq(JxbEquipment::getDelFlag, 0).or().isNull(JxbEquipment::getDelFlag));
    }

    /** 出租：审核通过且未删除。 */
    public static LambdaQueryWrapper<JxbChuzu> visibleChuzu() {
        return Wrappers.<JxbChuzu>lambdaQuery()
                .eq(JxbChuzu::getAuditStatus, AUDIT_APPROVED)
                .and(w -> w.isNull(JxbChuzu::getDelFlag).or().ne(JxbChuzu::getDelFlag, "1"));
    }

    /** 求租：审核通过且未删除。 */
    public static LambdaQueryWrapper<JxbQiuzu> visibleQiuzu() {
        return Wrappers.<JxbQiuzu>lambdaQuery()
                .eq(JxbQiuzu::getAuditStatus, AUDIT_APPROVED)
                .and(w -> w.isNull(JxbQiuzu::getDelFlag).or().ne(JxbQiuzu::getDelFlag, "1"));
    }

    /** 用机需求：审核通过且未删除。 */
    public static LambdaQueryWrapper<JxbXuqiu> visibleXuqiu() {
        return Wrappers.<JxbXuqiu>lambdaQuery()
                .eq(JxbXuqiu::getAuditStatus, AUDIT_APPROVED)
                .and(w -> w.isNull(JxbXuqiu::getDelFlag).or().ne(JxbXuqiu::getDelFlag, "1"));
    }

    /** 新机询价：审核通过且未删除。 */
    public static LambdaQueryWrapper<JxbXunjia> visibleXunjia() {
        return Wrappers.<JxbXunjia>lambdaQuery()
                .eq(JxbXunjia::getAuditStatus, AUDIT_APPROVED)
                .and(w -> w.isNull(JxbXunjia::getDelFlag).or().ne(JxbXunjia::getDelFlag, "1"));
    }

    /** 资讯：已发布且未删除。 */
    public static LambdaQueryWrapper<CmsArticle> visibleArticle() {
        return Wrappers.<CmsArticle>lambdaQuery()
                .eq(CmsArticle::getIsRelease, ARTICLE_RELEASED)
                .and(w -> w.isNull(CmsArticle::getDelFlag).or().ne(CmsArticle::getDelFlag, "1"));
    }
}
