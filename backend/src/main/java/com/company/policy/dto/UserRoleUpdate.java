package com.company.policy.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record UserRoleUpdate(
        @NotNull
                List<@NotNull @Pattern(regexp = RoleDefinitionUpdate.CODE_PATTERN) String>
                        roleCodes,
        @NotNull @Positive Integer expectedVersion) {}
