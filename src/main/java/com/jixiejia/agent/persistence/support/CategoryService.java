package com.jixiejia.agent.persistence.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.jxj.SysCategory;
import com.jixiejia.agent.persistence.mapper.jxj.SysCategoryMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备分类解析。sys_category 按 grade 分三级：1 机型 / 2 品牌 / 3 型号系列。
 *
 * <p>jxb_equipment 与 jxb_chuzu 都用 first/second/third_cate_id 指向这三级的 id。
 * 注意不要拿 jxb_equipment.brand_id 当品牌——那列指向 pms_brand，但库里的数据与设备名
 * 对不上（例如名字是"日立ZX200-3挖掘机"的车，brand_id 却指向三一重工），
 * 权威的品牌是 second_cate_id。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    public static final int GRADE_MACHINE = 1;
    public static final int GRADE_BRAND = 2;
    public static final int GRADE_MODEL = 3;

    private final SysCategoryMapper sysCategoryMapper;

    private volatile Map<Long, SysCategory> byId = Map.of();
    private volatile Map<Long, List<SysCategory>> byParent = Map.of();

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 重新载入全部分类。 */
    public synchronized void refresh() {
        List<SysCategory> rows = sysCategoryMapper.selectList(
                Wrappers.<SysCategory>lambdaQuery()
                        .and(w -> w.isNull(SysCategory::getDelFlag).or().ne(SysCategory::getDelFlag, "1"))
                        .orderByAsc(SysCategory::getGrade)
                        .orderByAsc(SysCategory::getSort)
                        .orderByAsc(SysCategory::getId));

        Map<Long, SysCategory> idMap = new LinkedHashMap<>();
        Map<Long, List<SysCategory>> parentMap = new LinkedHashMap<>();
        for (SysCategory c : rows) {
            idMap.put(c.getId(), c);
            if (c.getParentId() != null) {
                parentMap.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }

        this.byId = Map.copyOf(idMap);
        this.byParent = Map.copyOf(parentMap);
        log.info("设备分类载入完成：{} 条", rows.size());
    }

    /** 分类 id → 名称，查不到返回 null。 */
    public String name(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        SysCategory c = byId.get(categoryId);
        return c != null ? trimName(c.getName()) : null;
    }

    /** 分类 id → 名称，查不到时返回 null；用于拼接展示文本。 */
    public String nameOrEmpty(Long categoryId) {
        String n = name(categoryId);
        return n != null ? n : "";
    }

    /** 按名称找一级机型（如"挖掘机"），用于把用户口语映射成 first_cate_id。 */
    public Long findMachineId(String keyword) {
        return findIdByName(keyword, GRADE_MACHINE);
    }

    /** 按名称找品牌（如"小松"），用于映射 second_cate_id。 */
    public Long findBrandId(String keyword) {
        return findIdByName(keyword, GRADE_BRAND);
    }

    private Long findIdByName(String keyword, int grade) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String kw = keyword.trim().replaceAll("\\s+", "");
        Long prefixHit = null;
        for (SysCategory c : byId.values()) {
            if (c.getGrade() == null || c.getGrade() != grade) {
                continue;
            }
            String name = trimName(c.getName());
            if (name == null) {
                continue;
            }
            if (name.equals(kw)) {
                return c.getId();
            }
            if (prefixHit == null && kw.length() >= 2 && name.startsWith(kw)) {
                prefixHit = c.getId();
            }
        }
        return prefixHit;
    }

    /** 该分类下的子分类。 */
    public List<SysCategory> children(Long parentId) {
        if (parentId == null) {
            return List.of();
        }
        return byParent.getOrDefault(parentId, List.of());
    }

    /** 全部一级机型。 */
    public List<SysCategory> machines() {
        return byId.values().stream()
                .filter(c -> c.getGrade() != null && c.getGrade() == GRADE_MACHINE)
                .toList();
    }

    /**
     * 库里的分类名普遍带前导空格（如" 挖掘机"），展示前统一去掉。
     */
    static String trimName(String name) {
        return name == null ? null : name.trim().replaceAll("\\s+", "");
    }
}
