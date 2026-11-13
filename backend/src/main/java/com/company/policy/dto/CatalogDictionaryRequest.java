package com.company.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class CatalogDictionaryRequest {
    private CatalogDictionaryRequest() {}

    public enum Type {
        brand,
        series,
        color
    }

    public record Create(@NotBlank @Size(max = 64) String name, @Positive Long parentId) {}

    public record Rename(
            @NotBlank @Size(max = 64) String name, @NotBlank @Size(max = 64) String expectedName) {}
}
