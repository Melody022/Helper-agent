package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiRole;
import com.jixiejia.agent.persistence.entity.ai.AiRoleTool;
import com.jixiejia.agent.persistence.entity.ai.AiTool;
import com.jixiejia.agent.persistence.mapper.ai.AiRoleMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiRoleToolMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiToolMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表：把代码里实现了 {@link BizTool} 的 Bean 自动登记成可调用工具，
 * 并同步到 {@code ai_tool} 表，供管理台展示与角色授权。
 *
 * <p>职责边界要说清楚：<b>代码是工具定义的唯一来源</b>。
 * tool_name / capability / description / param_schema / impl_bean 一律以代码为准，
 * 每次启动覆盖；而 {@code status}（启停）和 {@code remark} 属于运营配置，
 * 启动时保留不动，否则管理台里停用的工具下次重启又会自己活过来。
 *
 * <p>角色白名单（ai_role_tool）只在"该角色一条授权都没有"时灌一份默认值，
 * 之后完全交给管理台维护，不会被启动流程覆盖。
 *
 * <p>同步失败不会阻断启动：内存注册表本身就是完整的，DB 只是镜像与配置载体。
 */
@Slf4j
@Service
public class ToolRegistry implements SmartInitializingSingleton {

    /** 一个已注册工具的元信息。 */
    public record ToolDescriptor(
            String name,
            String capability,
            String displayName,
            String description,
            String inputSchema,
            String implBean
    ) {
    }

    /** Spring 会把容器里所有 BizTool 实现按 Bean 名注入进来，这就是"自动注册"的来源。 */
    private final Map<String, BizTool> toolBeans;
    private final AiToolMapper aiToolMapper;
    private final AiRoleMapper aiRoleMapper;
    private final AiRoleToolMapper aiRoleToolMapper;

    private volatile Map<String, ToolCallback> callbacksByName = Map.of();
    private volatile Map<String, ToolDescriptor> descriptorsByName = Map.of();

    public ToolRegistry(Map<String, BizTool> toolBeans,
                        AiToolMapper aiToolMapper,
                        AiRoleMapper aiRoleMapper,
                        AiRoleToolMapper aiRoleToolMapper) {
        this.toolBeans = toolBeans;
        this.aiToolMapper = aiToolMapper;
        this.aiRoleMapper = aiRoleMapper;
        this.aiRoleToolMapper = aiRoleToolMapper;
    }

    @Override
    public void afterSingletonsInstantiated() {
        scan();
        try {
            syncToDatabase();
        } catch (Exception e) {
            log.warn("工具定义同步到 ai_tool 失败（不影响运行，内存注册表仍然可用）：{}", e.getMessage());
        }
    }

    /** 扫描所有 BizTool Bean，把 @Tool 方法转成 ToolCallback。 */
    private void scan() {
        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        Map<String, ToolDescriptor> descriptors = new LinkedHashMap<>();

        for (Map.Entry<String, BizTool> entry : toolBeans.entrySet()) {
            String beanName = entry.getKey();
            BizTool bean = entry.getValue();
            ToolCallback[] beanCallbacks = ToolCallbacks.from(bean);

            for (ToolCallback callback : beanCallbacks) {
                ToolDefinition def = callback.getToolDefinition();
                String name = def.name();
                if (callbacks.containsKey(name)) {
                    log.warn("工具名冲突：{} 在 {} 中重复定义，后者被忽略", name, beanName);
                    continue;
                }
                callbacks.put(name, callback);
                descriptors.put(name, new ToolDescriptor(
                        name, bean.capability(), bean.displayName(),
                        def.description(), def.inputSchema(), beanName));
            }
        }

        this.callbacksByName = Map.copyOf(callbacks);
        this.descriptorsByName = Map.copyOf(descriptors);
        log.info("工具注册完成：{} 个工具 / {} 个工具类：{}",
                callbacks.size(), toolBeans.size(), callbacks.keySet());
    }

    /** 把内存里的工具定义同步到 ai_tool，并为首批角色灌默认授权。 */
    private void syncToDatabase() {
        int inserted = 0;
        int updated = 0;

        for (ToolDescriptor d : descriptorsByName.values()) {
            AiTool existing = aiToolMapper.selectOne(
                    Wrappers.<AiTool>lambdaQuery().eq(AiTool::getToolName, d.name()));

            if (existing == null) {
                AiTool row = new AiTool();
                row.setToolName(d.name());
                row.setCapability(d.capability());
                row.setDisplayName(d.displayName());
                row.setDescription(d.description());
                row.setParamSchema(d.inputSchema());
                row.setImplBean(d.implBean());
                row.setStatus("0");
                row.setDelFlag("0");
                aiToolMapper.insert(row);
                inserted++;
            } else {
                // 只更新代码侧定义的字段，status / remark 是运营配置，保留
                existing.setCapability(d.capability());
                existing.setDisplayName(d.displayName());
                existing.setDescription(d.description());
                existing.setParamSchema(d.inputSchema());
                existing.setImplBean(d.implBean());
                aiToolMapper.updateById(existing);
                updated++;
            }
        }

        log.info("ai_tool 同步完成：新增 {} 条，更新 {} 条", inserted, updated);
        seedRoleToolsIfEmpty();
    }

    /**
     * 角色首次出现时，默认授予全部工具。
     * 只在"该角色当前没有任何授权"时执行，避免覆盖管理台的调整。
     */
    private void seedRoleToolsIfEmpty() {
        List<AiTool> allTools = aiToolMapper.selectList(
                Wrappers.<AiTool>lambdaQuery().eq(AiTool::getStatus, "0"));
        if (allTools.isEmpty()) {
            return;
        }

        List<AiRole> roles = aiRoleMapper.selectList(
                Wrappers.<AiRole>lambdaQuery().eq(AiRole::getStatus, "0"));

        for (AiRole role : roles) {
            Long existing = aiRoleToolMapper.selectCount(
                    Wrappers.<AiRoleTool>lambdaQuery().eq(AiRoleTool::getRoleId, role.getId()));
            if (existing != null && existing > 0) {
                continue;
            }
            for (AiTool tool : allTools) {
                AiRoleTool link = new AiRoleTool();
                link.setRoleId(role.getId());
                link.setToolId(tool.getId());
                aiRoleToolMapper.insert(link);
            }
            log.info("角色 {} 首次初始化，默认授予 {} 个工具", role.getRoleKey(), allTools.size());
        }
    }

    // ------------------------------------------------------------------
    // 运行时查询
    // ------------------------------------------------------------------

    /** 全部已注册工具的元信息。 */
    public Collection<ToolDescriptor> descriptors() {
        return descriptorsByName.values();
    }

    /** 全部已注册工具名。 */
    public Set<String> allToolNames() {
        return descriptorsByName.keySet();
    }

    /**
     * 按工具名取 ToolCallback，供 ChatClient 挂载。
     * 未注册的名字直接忽略——可能是管理台里配置了但代码已删除的历史工具。
     */
    public ToolCallback[] callbacksFor(Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return new ToolCallback[0];
        }
        return toolNames.stream()
                .map(callbacksByName::get)
                .filter(java.util.Objects::nonNull)
                .toArray(ToolCallback[]::new);
    }

    /** 某能力标识下的全部工具名。 */
    public Set<String> toolNamesForCapabilities(Collection<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return Set.of();
        }
        Set<String> caps = Set.copyOf(capabilities);
        Set<String> result = new LinkedHashSet<>();
        descriptorsByName.values().stream()
                .filter(d -> caps.contains(d.capability()))
                .forEach(d -> result.add(d.name()));
        return result;
    }

    /**
     * 某角色可用的工具名 = 角色授权 ∩ 当前启用 ∩ 代码里仍存在。
     * 三个条件缺一不可：授权表可能留着已删工具，启停状态可能刚被管理台改过。
     */
    public Set<String> toolNamesForRole(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return Set.of();
        }
        AiRole role = aiRoleMapper.selectOne(
                Wrappers.<AiRole>lambdaQuery().eq(AiRole::getRoleKey, roleKey));
        if (role == null) {
            log.warn("角色不存在：{}", roleKey);
            return Set.of();
        }

        List<AiRoleTool> links = aiRoleToolMapper.selectList(
                Wrappers.<AiRoleTool>lambdaQuery().eq(AiRoleTool::getRoleId, role.getId()));
        if (links.isEmpty()) {
            return Set.of();
        }

        List<Long> toolIds = links.stream().map(AiRoleTool::getToolId).toList();
        List<AiTool> enabled = aiToolMapper.selectList(
                Wrappers.<AiTool>lambdaQuery()
                        .in(AiTool::getId, toolIds)
                        .eq(AiTool::getStatus, "0"));

        Set<String> result = new LinkedHashSet<>();
        for (AiTool tool : enabled) {
            if (descriptorsByName.containsKey(tool.getToolName())) {
                result.add(tool.getToolName());
            }
        }
        return result;
    }
}
