package com.adtek.sn.support;

import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 与原系统 Python {@code json.dumps(obj, ensure_ascii=False, sort_keys=...)} 输出一致的序列化（默认分隔符 ", " 与 ":
 * "）， 用于库里已有的 JSON 列（如 sn_line_rule.custom_json）保持同一写法；读取用 {@link #parse}。
 */
public final class PyJson {
    private PyJson() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static String dumps(Object v, boolean sortKeys) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, sortKeys);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, boolean sortKeys) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof CharSequence s) {
            sb.append(JSON.writeValueAsString(s.toString()));
        } else if (v instanceof Map<?, ?> m) {
            Map<String, Object> items =
                    sortKeys ? new TreeMap<>((Map<String, Object>) m) : (Map<String, Object>) m;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : items.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(JSON.writeValueAsString(e.getKey())).append(": ");
                write(sb, e.getValue(), sortKeys);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                write(sb, x, sortKeys);
            }
            sb.append(']');
        } else {
            sb.append(JSON.writeValueAsString(v.toString()));
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json) {
        return JSON.readValue(json, LinkedHashMap.class);
    }
}
