package com.jixiejia.agent.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jixiejia.agent.persistence.entity.ai.AiRole;
import com.jixiejia.agent.persistence.entity.ai.AiRoleTool;
import com.jixiejia.agent.persistence.entity.ai.AiTool;
import com.jixiejia.agent.persistence.mapper.ai.AiRoleMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiRoleToolMapper;
import com.jixiejia.agent.persistence.mapper.ai.AiToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具白名单的管理侧操作，供管理台/管理接口使用。
 *
 * <p>运维语义上要区分两件事，接口也据此分开：
 * <ul>
 *   <li><b>启停工具</b>（{@code ai_tool.status}）—— 全局生效，所有角色都拿不到；</li>
 *   <li><b>角色授权</b>（{@code ai_role_tool}）—— 只影响该角色。</li>
 * </ul>
 * 停用优先于授权：即使某角色被授权了某工具，工具本身停用后依然拿不到
 * （判据见 {@link ToolRegistry#toolNamesForRole}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolAdminService {

    private final AiToolMapper aiToolMapper;
    private final AiRoleMapper aiRoleMapper;
    private final AiRoleToolMapper aiRoleToolMapper;
    private final ToolRegistry toolRegistry;

    /** 一个工具在管理台的展示项。 */
    public record ToolView(Long id, String toolName, String capability, String displayName,
                           String description, boolean enabled, String implBean) {
    }

    /** 角色 → 已授权工具名。 */
    public record RoleToolsView(Long roleId, String roleKey, String roleName, Set<String> toolNames) {
    }

    /** 全部工具（含未启用）。 */
    public List<ToolView> listTools() {
        List<AiTool> rows = aiToolMapper.selectList(
                Wrappers.<AiTool>lambdaQuery().orderByAsc(AiTool::getCapability).orderByAsc(AiTool::getToolName));
        List<ToolView> result = new ArrayList<>(rows.size());
        for (AiTool t : rows) {
            result.add(new ToolView(t.getId(), t.getToolName(), t.getCapability(),
                    t.getDisplayName(), t.getDescription(),
                    "0".equals(t.getStatus()), t.getImplBean()));
        }
        return result;
    }

    /** 各角色当前的白名单。 */
    public List<RoleToolsView> listRoleTools() {
        List<AiRole> roles = aiRoleMapper.selectList(
                Wrappers.<AiRole>lambdaQuery().eq(AiRole::getStatus, "0").orderByAsc(AiRole::getSort));
        if (roles.isEmpty()) {
            return List.of();
        }

        List<AiRoleTool> links = aiRoleToolMapper.selectList(Wrappers.<AiRoleTool>lambdaQuery());
        Map<Long, Set<Long>> toolIdsByRole = new LinkedHashMap<>();
        for (AiRoleTool link : links) {
            toolIdsByRole.computeIfAbsent(link.getRoleId(), k -> new LinkedHashSet<>()).add(link.getToolId());
        }

        Map<Long, String> nameById = new LinkedHashMap<>();
        for (AiTool t : aiToolMapper.selectList(Wrappers.<AiTool>lambdaQuery())) {
            nameById.put(t.getId(), t.getToolName());
        }

        List<RoleToolsView> result = new ArrayList<>(roles.size());
        for (AiRole role : roles) {
            Set<String> names = new LinkedHashSet<>();
            for (Long toolId : toolIdsByRole.getOrDefault(role.getId(), Set.of())) {
                String name = nameById.get(toolId);
                if (name != null) {
                    names.add(name);
                }
            }
            result.add(new RoleToolsView(role.getId(), role.getRoleKey(), role.getRoleName(), names));
        }
        return result;
    }

    /**
     * 启停某个工具，全局生效。
     *
     * @return 工具不存在时返回 false
     */
    public boolean setToolEnabled(String toolName, boolean enabled) {
        AiTool tool = aiToolMapper.selectOne(
                Wrappers.<AiTool>lambdaQuery().eq(AiTool::getToolName, toolName));
        if (tool == null) {
            return false;
        }
        AiTool update = new AiTool();
        update.setId(tool.getId());
        update.setStatus(enabled ? "0" : "1");
        aiToolMapper.updateById(update);
        log.info("工具 {} 已{}", toolName, enabled ? "启用" : "停用");
        return true;
    }

    /**
     * 覆盖式设置某角色的工具白名单。
     *
     * <p>用"覆盖"而不是"增删"：管理台提交的是一份完整名单，
     * 增量接口容易因为前端状态不同步而出现幽灵授权。
     *
     * @return 角色不存在时返回 false；传入的工具名中不存在的会被忽略并记日志
     */
    @Transactional
    public boolean setRoleTools(String roleKey, Set<String> toolNames) {
        AiRole role = aiRoleMapper.selectOne(
                Wrappers.<AiRole>lambdaQuery().eq(AiRole::getRoleKey, roleKey));
        if (role == null) {
            return false;
        }

        Set<String> requested = toolNames == null ? Set.of() : toolNames;
        List<AiTool> tools = requested.isEmpty()
                ? List.of()
                : aiToolMapper.selectList(
                        Wrappers.<AiTool>lambdaQuery().in(AiTool::getToolName, requested));

        Set<String> found = new LinkedHashSet<>();
        for (AiTool t : tools) {
            found.add(t.getToolName());
        }
        Set<String> missing = new LinkedHashSet<>(requested);
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            log.warn("角色 {} 的白名单里包含未注册的工具，已忽略：{}", roleKey, missing);
        }

        aiRoleToolMapper.delete(
                Wrappers.<AiRoleTool>lambdaQuery().eq(AiRoleTool::getRoleId, role.getId()));

        for (AiTool t : tools) {
            AiRoleTool link = new AiRoleTool();
            link.setRoleId(role.getId());
            link.setToolId(t.getId());
            aiRoleToolMapper.insert(link);
        }

        log.info("角色 {} 的工具白名单已更新：{}", roleKey, found);
        return true;
    }

    /** 代码里注册了、但库里还没有的工具（同步失败时会看到，便于排查）。 */
    public Set<String> unsyncedToolNames() {
        Set<String> registered = toolRegistry.allToolNames();
        Set<String> inDb = new LinkedHashSet<>();
        for (AiTool t : aiToolMapper.selectList(Wrappers.<AiTool>lambdaQuery())) {
            inDb.add(t.getToolName());
        }
        Set<String> missing = new LinkedHashSet<>(registered);
        missing.removeAll(inDb);
        return missing;
    }
}
