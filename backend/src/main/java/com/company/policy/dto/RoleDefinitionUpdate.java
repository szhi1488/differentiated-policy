package com.company.policy.dto;

import java.util.List;

public record RoleDefinitionUpdate(
        String roleCode,
        String roleName,
        Boolean enabled,
        List<String> permissions,
        Integer expectedVersion) {
    public static final String CODE_PATTERN = "[A-Z][A-Z0-9_]{0,63}";
}
