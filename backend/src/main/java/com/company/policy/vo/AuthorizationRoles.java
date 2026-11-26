package com.company.policy.vo;

import java.util.List;

public record AuthorizationRoles(List<Role> roles, List<Permission> permissions) {
    public record Role(
            String id,
            String roleCode,
            String roleName,
            boolean builtin,
            boolean enabled,
            int version,
            List<String> permissions) {}

    public record Permission(String code, String name) {}
}
