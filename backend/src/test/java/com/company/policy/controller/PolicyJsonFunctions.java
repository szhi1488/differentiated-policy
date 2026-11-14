package com.company.policy.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class PolicyJsonFunctions {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PolicyJsonFunctions() {}

    public static String quote(String value) throws JsonProcessingException {
        return JSON.writeValueAsString(value);
    }

    // H2 缺少 MySQL JSON_CONTAINS，此处仅模拟整单平台数组中的字符串精确匹配。
    public static boolean contains(String array, String value) throws JsonProcessingException {
        var target = JSON.readTree(value);
        for (var item : JSON.readTree(array)) {
            if (item.equals(target)) {
                return true;
            }
        }
        return false;
    }
}
