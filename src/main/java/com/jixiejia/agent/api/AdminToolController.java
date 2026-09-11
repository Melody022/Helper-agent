package com.jixiejia.agent.api;

import com.jixiejia.agent.api.dto.RoleToolsRequest;
import com.jixiejia.agent.auth.CurrentUser;
import com.jixiejia.agent.auth.RequireRole;
import com.jixiejia.agent.tool.ToolAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工具与白名单管理。整个类要求 ADMIN 角色。
 *
 * <p>这块本来排在 M9 的管理台里。之所以提前做成接口：鉴权刚落地，
 * 而"哪些角色能用哪些工具"是上线前就要反复调的东西，
 * 没有接口就只能直接改库，容易改错也没留痕。
 * M9 的 Thymeleaf 页面直接复用这几个接口即可，不浪费。
 */
@Tag(name = "管理-工具白名单", description = "工具启停与角色授权，需 ADMIN")
@RestController
@RequestMapping("/api/admin/tools")
@RequireRole(CurrentUser.ROLE_ADMIN)
@RequiredArgsConstructor
public class AdminToolController {

    private final ToolAdminService toolAdminService;

    @Operation(summary = "工具列表", description = "含停用中的工具")
    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of(
                "tools", toolAdminService.listTools(),
                "unsyncedFromCode", toolAdminService.unsyncedToolNames()));
    }

    @Operation(summary = "角色白名单", description = "各角色当前被授权了哪些工具")
    @GetMapping("/roles")
    public ResponseEntity<?> roleTools() {
        return ResponseEntity.ok(toolAdminService.listRoleTools());
    }

    @Operation(summary = "启停工具", description = "全局生效，停用后任何角色都拿不到该工具")
    @PutMapping("/{toolName}/status")
    public ResponseEntity<?> setStatus(@PathVariable String toolName,
                                       @RequestParam boolean enabled) {
        if (!toolAdminService.setToolEnabled(toolName, enabled)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", 404, "message", "工具不存在：" + toolName));
        }
        return ResponseEntity.ok(Map.of("code", 200, "message", "已更新"));
    }

    @Operation(summary = "设置角色白名单", description = "覆盖式：提交的是该角色的完整工具名单")
    @PutMapping("/roles/{roleKey}")
    public ResponseEntity<?> setRoleTools(@PathVariable String roleKey,
                                          @RequestBody RoleToolsRequest request) {
        if (!toolAdminService.setRoleTools(roleKey, request.toolNames())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", 404, "message", "角色不存在：" + roleKey));
        }
        return ResponseEntity.ok(Map.of("code", 200, "message", "已更新"));
    }
}
