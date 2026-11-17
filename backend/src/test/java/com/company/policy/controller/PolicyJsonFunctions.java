package com.company.policy.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;

public final class PolicyJsonFunctions {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PolicyJsonFunctions() {}

    public static String quote(String value) throws JsonProcessingException {
        return JSON.writeValueAsString(value);
    }

    // 仅供 H2 验证管理员查询使用的单值路径，缺失路径与 JSON null 保持不同语义。
    public static String extract(String document, String path) throws JsonProcessingException {
        if (document == null || path == null) return null;
        if (!path.matches("\\$\\.[A-Za-z]+(?:\\.[A-Za-z]+)*")) {
            throw new IllegalArgumentException("测试未支持该 JSON_EXTRACT 路径");
        }
        var node = JSON.readTree(document);
        for (var field : path.substring(2).split("\\.")) {
            node = node.path(field);
        }
        return node.isMissingNode() ? null : JSON.writeValueAsString(node);
    }

    public static String unquote(String value) throws JsonProcessingException {
        if (value == null) return null;
        var node = JSON.readTree(value);
        return node.isTextual() ? node.textValue() : value;
    }

    // H2 缺少 MySQL JSON_CONTAINS，模拟平台字符串及附件嵌套对象的包含判断。
    public static boolean contains(String array, String value) throws JsonProcessingException {
        return array != null
                && value != null
                && containsNode(JSON.readTree(array), JSON.readTree(value));
    }

    // 仅模拟管理员关键词查询实际使用的 JSON_SEARCH 路径，覆盖 SQL LIKE 的转义语义。
    public static String search(
            String document, String mode, String value, String escape, String path)
            throws JsonProcessingException {
        if (!"one".equals(mode) || !"$.goods[*].modelname".equals(path) || escape.length() != 1) {
            throw new IllegalArgumentException("测试未支持该 JSON_SEARCH 用法");
        }
        var regex = new StringBuilder("(?s)^");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == escape.charAt(0) && i + 1 < value.length()) {
                regex.append(Pattern.quote(String.valueOf(value.charAt(++i))));
            } else if (ch == '%') {
                regex.append(".*");
            } else if (ch == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        var pattern = Pattern.compile(regex.append('$').toString());
        var goods = JSON.readTree(document).path("goods");
        for (int i = 0; i < goods.size(); i++) {
            if (pattern.matcher(goods.get(i).path("modelname").asText()).matches()) {
                return JSON.writeValueAsString("$.goods[" + i + "].modelname");
            }
        }
        return null;
    }

    private static boolean containsNode(JsonNode container, JsonNode target) {
        if (container == null) return false;
        if (container.isArray()) {
            if (target.isArray()) {
                for (var item : target) {
                    if (!containsNode(container, item)) return false;
                }
                return true;
            }
            for (var item : container) {
                if (containsNode(item, target)) return true;
            }
            return false;
        }
        if (container.isObject() && target.isObject()) {
            var fields = target.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!containsNode(container.get(field.getKey()), field.getValue())) return false;
            }
            return true;
        }
        return container.equals(target);
    }
}
