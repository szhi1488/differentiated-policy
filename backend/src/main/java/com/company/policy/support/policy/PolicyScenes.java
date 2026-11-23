package com.company.policy.support.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

public final class PolicyScenes {
    public static final List<String> CODES = List.of("NATIONAL_SUB", "LOCAL_SUB", "SELF_SUB");

    private PolicyScenes() {}

    public static List<String> fromLegacy(List<String> scenes, String legacy) {
        return scenes == null && legacy != null ? List.of(legacy) : scenes;
    }

    public static String single(List<String> scenes) {
        return scenes != null && scenes.size() == 1 ? scenes.get(0) : null;
    }

    // 主表与新快照使用数组。管理员列表还会读取旧快照中原样提取的 JSON 字符串。
    public static List<String> read(ObjectMapper json, String stored) {
        if (stored == null) return null;
        try {
            return json.readerForListOf(String.class)
                    .with(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                    .readValue(stored);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取已保存的政策场景。", error);
        }
    }
}
