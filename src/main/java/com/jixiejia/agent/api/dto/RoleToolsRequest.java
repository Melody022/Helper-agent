package com.jixiejia.agent.api.dto;

import java.util.Set;

/**
 * 覆盖式设置某角色的工具白名单。
 */
public record RoleToolsRequest(Set<String> toolNames) {
}
