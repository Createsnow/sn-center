package com.adtek.sn.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 文档型数据（库里以 JSON 保存的生成计划、预演结果等，键为 snake_case）返回给前端前统一转为 camelCase 键；只转键，不改值。 */
public final class Camel {
    private Camel() {}

    public static String key(String k) {
        if (k.indexOf('_') < 0) {
            return k;
        }
        StringBuilder sb = new StringBuilder(k.length());
        boolean up = false;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c == '_' && i > 0) {
                up = true;
            } else if (up) {
                sb.append(Character.toUpperCase(c));
                up = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Object convert(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(key(String.valueOf(e.getKey())), convert(e.getValue()));
            }
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object x : c) {
                out.add(convert(x));
            }
            return out;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, ?> m) {
        return (Map<String, Object>) convert(m);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(List<? extends Map<String, ?>> m) {
        return (List<Map<String, Object>>) convert(m);
    }
}
