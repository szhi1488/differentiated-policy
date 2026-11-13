package com.company.policy.vo;

import com.company.policy.dto.CatalogDictionaryRequest.Type;

public record CatalogDictionaryEntry(
        Type type, String id, String parentId, String name, boolean inUse) {}
