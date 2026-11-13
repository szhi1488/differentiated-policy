package com.company.policy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CatalogGoodsCreate(
        @NotBlank @Size(max = 384) String name,
        @NotNull @Positive Long brandId,
        @NotNull @Positive Long seriesId,
        @NotBlank @Size(max = 100) String modelName,
        @Size(max = 64) String edition,
        @NotBlank @Size(max = 20) String ram,
        @NotBlank @Size(max = 20) String rom,
        @NotNull @Positive Long colorId,
        @NotNull @Min(1) @Max(4) Integer status) {}
