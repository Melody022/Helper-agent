package com.jixiejia.agent.persistence.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.jxj.BizBaseRegion;
import com.jixiejia.agent.persistence.mapper.jxj.BizBaseRegionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 省市区解析。基于 {@code biz_base_region}——它的 id 就是 12 位行政区划码，
 * 与业务表 province_id / city_id / district_id 直接相等。
 *
 * <p>库里还有 region / ls_province / ls_city / ls_district 几张同义表，都是干扰项，不要用。
 *
 * <p>两个方向的转换都要有：
 * <ul>
 *   <li>id → 中文：工具把查询结果拼给模型时用；</li>
 *   <li>中文 → id：用户说"洛阳有挖掘机出租吗"，得先把"洛阳"落成 410300000000 才能查库。
 *       用户不会说全称，所以要容忍"洛阳"/"洛阳市"、"广西"/"广西壮族自治区"这类差异。</li>
 * </ul>
 *
 * <p>数据量约 3900 行且几乎不变，启动时全量载入内存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionService {

    public static final int LEVEL_PROVINCE = 1;
    public static final int LEVEL_CITY = 2;
    public static final int LEVEL_DISTRICT = 3;

    /** 去掉这些后缀后做匹配，"洛阳市"和"洛阳"才能对上 */
    private static final String[] NAME_SUFFIXES = {
            "特别行政区", "自治区", "自治州", "自治县", "地区", "盟", "省", "市", "区", "县"
    };

    private final BizBaseRegionMapper regionMapper;

    private volatile Map<Long, BizBaseRegion> byId = Map.of();
    private volatile Map<Long, List<BizBaseRegion>> byParent = Map.of();
    private volatile List<BizBaseRegion> all = List.of();

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 重新载入全部区划。 */
    public synchronized void refresh() {
        List<BizBaseRegion> rows = regionMapper.selectList(
                Wrappers.<BizBaseRegion>lambdaQuery()
                        .and(w -> w.isNull(BizBaseRegion::getDelFlag).or().ne(BizBaseRegion::getDelFlag, "1"))
                        .orderByAsc(BizBaseRegion::getLevel)
                        .orderByAsc(BizBaseRegion::getSort)
                        .orderByAsc(BizBaseRegion::getId));

        Map<Long, BizBaseRegion> idMap = new LinkedHashMap<>();
        Map<Long, List<BizBaseRegion>> parentMap = new LinkedHashMap<>();
        for (BizBaseRegion r : rows) {
            idMap.put(r.getId(), r);
            if (r.getParentId() != null) {
                parentMap.computeIfAbsent(r.getParentId(), k -> new ArrayList<>()).add(r);
            }
        }

        this.byId = Map.copyOf(idMap);
        this.byParent = Map.copyOf(parentMap);
        this.all = List.copyOf(rows);
        log.info("行政区划载入完成：{} 条", rows.size());
    }

    /** 区划码 → 名称，查不到返回 null。 */
    public String name(Long regionId) {
        if (regionId == null) {
            return null;
        }
        BizBaseRegion r = byId.get(regionId);
        return r != null ? r.getName() : null;
    }

    /** 批量 id → 名称，查不到的键不会出现。 */
    public Map<Long, String> namesOf(Collection<Long> regionIds) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (regionIds == null) {
            return result;
        }
        for (Long id : regionIds) {
            String n = name(id);
            if (n != null) {
                result.put(id, n);
            }
        }
        return result;
    }

    /**
     * 拼出可读位置，如 "河南省洛阳市洛龙区"。
     * 直辖市会出现省市同名（北京市/北京市/东城区），同名层级会去重成 "北京市东城区"。
     */
    public String fullName(Long provinceId, Long cityId, Long districtId) {
        StringBuilder sb = new StringBuilder();
        String p = name(provinceId);
        String c = name(cityId);
        String d = name(districtId);

        if (p != null) {
            sb.append(p);
        }
        if (c != null && !c.equals(p)) {
            sb.append(c);
        }
        if (d != null && !d.equals(c)) {
            sb.append(d);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 下级区划。 */
    public List<BizBaseRegion> children(Long parentId) {
        if (parentId == null) {
            return List.of();
        }
        return byParent.getOrDefault(parentId, List.of());
    }

    /** 按层级取全部：1 省 / 2 市 / 3 区县。 */
    public List<BizBaseRegion> byLevel(int level) {
        return all.stream().filter(r -> r.getLevel() != null && r.getLevel() == level).toList();
    }

    /**
     * 按名称模糊查找区划。level 传 null 表示不限层级。
     *
     * <p>排序优先级：全等 &gt; 去后缀后相等 &gt; 去后缀后前缀命中。
     * 前缀命中是为了兜住"广西"→"广西壮族自治区"、"新疆"→"新疆维吾尔自治区"这类简称。
     */
    public List<BizBaseRegion> search(String keyword, Integer level) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String raw = keyword.trim().replaceAll("\\s+", "");
        String norm = normalize(raw);

        List<Scored> hits = new ArrayList<>();
        for (BizBaseRegion r : all) {
            if (level != null && !level.equals(r.getLevel())) {
                continue;
            }
            String regionName = r.getName();
            if (regionName == null) {
                continue;
            }
            String regionNorm = normalize(regionName);

            int score;
            if (regionName.equals(raw)) {
                score = 0;
            } else if (regionNorm.equals(norm)) {
                score = 1;
            } else if (norm.length() >= 2 && regionNorm.startsWith(norm)) {
                score = 2;
            } else {
                continue;
            }
            hits.add(new Scored(r, score));
        }

        hits.sort(Comparator.comparingInt((Scored s) -> s.score)
                .thenComparingInt(s -> s.region.getLevel() == null ? 9 : s.region.getLevel())
                .thenComparingInt(s -> s.region.getSort() == null ? Integer.MAX_VALUE : s.region.getSort())
                .thenComparingLong(s -> s.region.getId()));

        return hits.stream().map(s -> s.region).toList();
    }

    /**
     * 名称 → 区划码。level 传 null 表示不限层级；查不到返回 null。
     * 有歧义时取排序最靠前的一条（同业常见做法，如"朝阳"会命中北京朝阳区）。
     */
    public Long resolveId(String name, Integer level) {
        List<BizBaseRegion> found = search(name, level);
        return found.isEmpty() ? null : found.get(0).getId();
    }

    /** 名称 → 省 id。 */
    public Long resolveProvinceId(String name) {
        return resolveId(name, LEVEL_PROVINCE);
    }

    /** 名称 → 市 id。 */
    public Long resolveCityId(String name) {
        return resolveId(name, LEVEL_CITY);
    }

    /** 名称 → 区县 id（限定在指定城市下，避免"长安区"在多个城市重名）。 */
    public Long resolveDistrictId(String cityName, String districtName) {
        Long cityId = resolveCityId(cityName);
        if (cityId == null) {
            return resolveId(districtName, LEVEL_DISTRICT);
        }
        return children(cityId).stream()
                .filter(r -> normalize(r.getName()).equals(normalize(districtName)))
                .map(BizBaseRegion::getId)
                .findFirst()
                .orElseGet(() -> resolveId(districtName, LEVEL_DISTRICT));
    }

    /** 判断某个区划码是否属于给定省（用于"附近/本省"这类降级查询）。 */
    public boolean belongsToProvince(Long regionId, Long provinceId) {
        Set<Long> chain = provinceChain(regionId);
        return provinceId != null && chain.contains(provinceId);
    }

    /**
     * 自底向上找出所属的省级区划。
     *
     * <p>用途：用户往往只说城市名（"洛阳"），但业务表 province_id 和 city_id 都要填。
     * 只填 city_id 的话，这条数据在按省筛选时就查不到——用户觉得"发了却没出现"，
     * 很难查到原因。
     *
     * @return 找不到时返回 null
     */
    public Long provinceIdOf(Long regionId) {
        return ancestorOfLevel(regionId, LEVEL_PROVINCE);
    }

    /** 自底向上找出所属的市级区划。 */
    public Long cityIdOf(Long regionId) {
        return ancestorOfLevel(regionId, LEVEL_CITY);
    }

    private Long ancestorOfLevel(Long regionId, int level) {
        BizBaseRegion cur = regionId == null ? null : byId.get(regionId);
        int guard = 0;
        while (cur != null && guard++ < 5) {
            if (cur.getLevel() != null && cur.getLevel() == level) {
                return cur.getId();
            }
            cur = cur.getParentId() == null ? null : byId.get(cur.getParentId());
        }
        return null;
    }

    /** 自底向上回溯出区划链（区县→市→省）。 */
    public Set<Long> provinceChain(Long regionId) {
        Set<Long> chain = new java.util.LinkedHashSet<>();
        BizBaseRegion cur = regionId == null ? null : byId.get(regionId);
        int guard = 0;
        while (cur != null && guard++ < 5) {
            chain.add(cur.getId());
            cur = cur.getParentId() == null ? null : byId.get(cur.getParentId());
        }
        return chain;
    }

    /** 去掉行政区划后缀并去掉空白，"洛阳市"→"洛阳"。 */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim().replaceAll("\\s+", "");
        boolean changed = true;
        while (changed && s.length() > 1) {
            changed = false;
            for (String suffix : NAME_SUFFIXES) {
                if (s.length() > suffix.length() && s.endsWith(suffix)) {
                    s = s.substring(0, s.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    private record Scored(BizBaseRegion region, int score) {
    }
}
