package com.jixiejia.agent.persistence;

import com.jixiejia.agent.persistence.entity.jxj.BizBaseRegion;
import com.jixiejia.agent.persistence.entity.jxj.CmsArticle;
import com.jixiejia.agent.persistence.entity.jxj.JxbChuzu;
import com.jixiejia.agent.persistence.entity.jxj.JxbEquipment;
import com.jixiejia.agent.persistence.mapper.jxj.BizBaseRegionMapper;
import com.jixiejia.agent.persistence.mapper.jxj.CmsArticleMapper;
import com.jixiejia.agent.persistence.mapper.jxj.JxbChuzuMapper;
import com.jixiejia.agent.persistence.mapper.jxj.JxbEquipmentMapper;
import com.jixiejia.agent.persistence.mapper.jxj.JxbQiuzuMapper;
import com.jixiejia.agent.persistence.mapper.jxj.JxbXuqiuMapper;
import com.jixiejia.agent.persistence.support.BizFilters;
import com.jixiejia.agent.persistence.support.DictService;
import com.jixiejia.agent.persistence.support.RegionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 数据层冒烟测试：直接打本机真实的 jxj 库。
 *
 * <p>断言里的行数是按当前种子数据写死的（设备 23 / 出租 14 / 求租 3 / 需求 6 / 资讯 13）。
 * 这些数字刻意写死——一旦有人改动了可见性过滤条件，行数变化会立刻让测试失败，
 * 而不是安静地少返回几条记录。若确实往库里补了数据，请同步更新这里的期望值。
 */
@SpringBootTest
class M2DataLayerTest {

    @Autowired
    private BizBaseRegionMapper regionMapper;

    @Autowired
    private JxbEquipmentMapper equipmentMapper;

    @Autowired
    private JxbChuzuMapper chuzuMapper;

    @Autowired
    private JxbQiuzuMapper qiuzuMapper;

    @Autowired
    private JxbXuqiuMapper xuqiuMapper;

    @Autowired
    private CmsArticleMapper articleMapper;

    @Autowired
    private RegionService regionService;

    @Autowired
    private DictService dictService;

    @Test
    @DisplayName("行政区划：id 与名称双向解析")
    void regionResolve() {
        // id → 名称
        assertThat(regionService.name(110000000000L)).isEqualTo("北京市");
        assertThat(regionService.name(410300000000L)).isEqualTo("洛阳市");

        // 名称 → id：用户不会说全称，要容忍"洛阳"/"洛阳市"
        assertThat(regionService.resolveCityId("洛阳")).isEqualTo(410300000000L);
        assertThat(regionService.resolveCityId("洛阳市")).isEqualTo(410300000000L);
        assertThat(regionService.resolveProvinceId("河南")).isEqualTo(410000000000L);

        // 直辖市省市同名，resolveCityId 必须按 level 取到市级那条
        assertThat(regionService.resolveCityId("北京")).isEqualTo(110100000000L);
        assertThat(regionService.resolveProvinceId("北京")).isEqualTo(110000000000L);

        // 自治区简称：前缀命中
        assertThat(regionService.resolveProvinceId("广西")).isEqualTo(450000000000L);

        // 全称拼接要去掉直辖市重复层级
        assertThat(regionService.fullName(110000000000L, 110100000000L, 110101000000L))
                .isEqualTo("北京市东城区");
        assertThat(regionService.fullName(410000000000L, 410300000000L, 410311000000L))
                .isEqualTo("河南省洛阳市洛龙区");

        // 区划链回溯：区 → 市 → 省
        assertThat(regionService.belongsToProvince(410311000000L, 410000000000L)).isTrue();
        assertThat(regionService.belongsToProvince(410311000000L, 110000000000L)).isFalse();

        // 全区划表规模固定，作为基准线的守卫
        assertThat(regionMapper.selectCount(null)).isEqualTo(3869L);
        assertThat(regionService.byLevel(RegionService.LEVEL_PROVINCE)).isNotEmpty();
    }

    @Test
    @DisplayName("字典：码值 ↔ 中文双向翻译")
    void dictTranslate() {
        assertThat(dictService.label("cz_sbzt", "dz")).isEqualTo("待租");
        assertThat(dictService.label("cz_sbzt", "ml")).isEqualTo("忙碌");
        assertThat(dictService.label("zl_sblx", "xwz")).isEqualTo("旋挖钻");
        assertThat(dictService.label("qz_fkfs", "gcqfk")).isEqualTo("工程期付款");
        assertThat(dictService.label("jxb_jxdw", "20-29")).isEqualTo("20-29吨");
        assertThat(dictService.label("audit_thress", "1")).isEqualTo("通过");

        // 反向：发布表单里用户选中文，落库要码值
        assertThat(dictService.valueOf("cz_sbzt", "待租")).isEqualTo("dz");
        assertThat(dictService.valueOf("qz_fkfs", "协商付款")).isEqualTo("xsfk");

        // 未知码值原样返回，不要变成 null 让模型瞎猜
        assertThat(dictService.labelOrRaw("cz_sbzt", "??")).isEqualTo("??");
        assertThat(dictService.label("cz_sbzt", "??")).isNull();

        assertThat(dictService.options("cz_sbzt")).hasSize(2);
        assertThat(dictService.items("qz_gclx")).hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("可见性过滤：各业务表行数符合平台口径")
    void visibilityFilters() {
        List<JxbEquipment> equipment = equipmentMapper.selectList(BizFilters.visibleEquipment());
        assertThat(equipment).hasSize(23);
        assertThat(equipment).allSatisfy(e -> assertThat(e.getShelvesStatus()).isEqualTo("1"));

        // 关键回归点：jxb_chuzu 活动行的 del_flag 是空串/NULL 而非 '0'，
        // 如果用 `del_flag = '0'` 过滤会一条都查不出来。
        List<JxbChuzu> chuzu = chuzuMapper.selectList(BizFilters.visibleChuzu());
        assertThat(chuzu).hasSize(14);
        assertThat(chuzu).allSatisfy(c -> {
            assertThat(c.getAuditStatus()).isEqualTo("1");
            assertThat(c.getDelFlag()).isNotEqualTo("1");
        });

        assertThat(qiuzuMapper.selectList(BizFilters.visibleQiuzu())).hasSize(3);
        assertThat(xuqiuMapper.selectList(BizFilters.visibleXuqiu())).hasSize(6);
        assertThat(articleMapper.selectList(BizFilters.visibleArticle())).hasSize(13);
    }

    @Test
    @DisplayName("保留字：cms_article.desc 需反引号转义才能查询")
    void reservedWordDescColumn() {
        CmsArticle article = articleMapper.selectById(7L);
        assertThat(article).isNotNull();
        assertThat(article.getDesc()).isNotBlank();
        assertThat(article.getTitle()).isNotBlank();
    }

    @Test
    @DisplayName("只读护栏：业务 Mapper 的写操作被拦截")
    void businessTablesAreReadOnly() {
        assertThatThrownBy(() -> equipmentMapper.deleteById(19L))
                .hasStackTraceContaining("业务表为只读");
        assertThatThrownBy(() -> chuzuMapper.deleteById(8L))
                .hasStackTraceContaining("业务表为只读");
    }
}
