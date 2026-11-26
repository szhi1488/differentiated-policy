package com.company.policy.support.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

public final class PolicyScenes {
    public static final List<String> CODES = List.of("NATIONAL_SUB", "LOCAL_SUB", "SELF_SUB");

    private PolicyScenes() {}

    public static List<String> read(ObjectMapper json, String stored) {
        if (stored == null) return null;
        try {
            return json.readerForListOf(String.class).readValue(stored);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取已保存的政策场景。", error);
        }
    }
}
