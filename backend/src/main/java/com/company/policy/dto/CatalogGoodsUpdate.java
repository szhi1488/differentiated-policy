package com.company.policy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CatalogGoodsUpdate(
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String expectedVersion,
        @NotNull @Valid CatalogGoodsWrite product) {}
