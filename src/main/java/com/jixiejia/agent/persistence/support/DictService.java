package com.jixiejia.agent.persistence.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.jxj.SysDictData;
import com.jixiejia.agent.persistence.mapper.jxj.SysDictDataMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典翻译。jxj 业务表大量字段落库的是字典码而非中文
 * （例如 jxb_chuzu.status = "dz"，jxb_qiuzu.pay_type = "gcqfk"），
 * 直接丢给大模型会得到一堆无法解释的缩写，所以取数后必须经这里转成中文。
 *
 * <p>字典是低频变更的配置数据，全量约 500 行，启动时一次性载入内存，避免每次对话都查库。
 * 管理台改完字典可调用 {@link #refresh()} 热更。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictService {

    private final SysDictDataMapper sysDictDataMapper;

    /** type -> (dictValue -> dictLabel)，按 dict_sort 保持顺序 */
    private volatile Map<String, Map<String, String>> labelByValue = Map.of();

    /** type -> (dictLabel -> dictValue)，发布表单里用户选中文、落库要码值时用 */
    private volatile Map<String, Map<String, String>> valueByLabel = Map.of();

    /** type -> 该类型下的全部字典项 */
    private volatile Map<String, List<SysDictData>> itemsByType = Map.of();

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 重新载入全部字典。 */
    public synchronized void refresh() {
        List<SysDictData> all = sysDictDataMapper.selectList(
                Wrappers.<SysDictData>lambdaQuery()
                        .eq(SysDictData::getStatus, "0")
                        .orderByAsc(SysDictData::getDictType)
                        .orderByAsc(SysDictData::getDictSort)
                        .orderByAsc(SysDictData::getDictCode));

        Map<String, Map<String, String>> byValue = new LinkedHashMap<>();
        Map<String, Map<String, String>> byLabel = new LinkedHashMap<>();
        Map<String, List<SysDictData>> items = new LinkedHashMap<>();

        for (SysDictData d : all) {
            if (d.getDictType() == null || d.getDictValue() == null) {
                continue;
            }
            items.computeIfAbsent(d.getDictType(), k -> new java.util.ArrayList<>()).add(d);
            byValue.computeIfAbsent(d.getDictType(), k -> new LinkedHashMap<>())
                    .putIfAbsent(d.getDictValue(), d.getDictLabel());
            if (d.getDictLabel() != null) {
                // 同 label 重复时保留排序靠前的那条
                byLabel.computeIfAbsent(d.getDictType(), k -> new LinkedHashMap<>())
                        .putIfAbsent(d.getDictLabel(), d.getDictValue());
            }
        }

        this.labelByValue = Collections.unmodifiableMap(byValue);
        this.valueByLabel = Collections.unmodifiableMap(byLabel);
        this.itemsByType = Collections.unmodifiableMap(items);
        log.info("字典载入完成：{} 种类型 / {} 条数据", items.size(), all.size());
    }

    /** 码值 → 中文；查不到返回 null。 */
    public String label(String dictType, String value) {
        if (value == null) {
            return null;
        }
        return labelByValue.getOrDefault(dictType, Map.of()).get(value);
    }

    /**
     * 码值 → 中文；查不到时原样返回码值。
     * 用于拼给模型的文本：宁可露出原始码，也不要变成 "null" 让模型误判。
     */
    public String labelOrRaw(String dictType, String value) {
        String label = label(dictType, value);
        return label != null ? label : value;
    }

    /** 中文 → 码值；查不到返回 null。 */
    public String valueOf(String dictType, String label) {
        if (label == null) {
            return null;
        }
        return valueByLabel.getOrDefault(dictType, Map.of()).get(label);
    }

    /** 某字典全部选项（value → label，有序）。 */
    public Map<String, String> options(String dictType) {
        return labelByValue.getOrDefault(dictType, Map.of());
    }

    /** 某字典全部条目。 */
    public List<SysDictData> items(String dictType) {
        return itemsByType.getOrDefault(dictType, List.of());
    }
}
