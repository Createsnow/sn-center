package com.adtek.sn.service.support;

import com.adtek.common.core.BusinessException;
import com.adtek.common.core.ErrorCode;
import com.adtek.sn.constant.ErrorCodeConstants;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** 生成与分配失败的公开原因白名单；数量参数保持十进制字符串，历史不保存异常原文。 */
public final class GenerationErrors {
    private static final Map<String, Set<String>> PARAMETERS =
            Map.of(
                    "GEN_PREVIEW_REQUIRED",
                    Set.of(),
                    "GEN_PREVIEW_USED",
                    Set.of(),
                    "GEN_PREVIEW_EXPIRED",
                    Set.of(),
                    "GEN_PREVIEW_CHANGED",
                    Set.of(),
                    "GEN_PREVIEW_STALE",
                    Set.of("pi", "expected", "actual"),
                    "GEN_QUOTA_EMPTY",
                    Set.of("bill_no"),
                    "GEN_QUOTA_EXCEEDED",
                    Set.of("qty", "quota"),
                    "ALLOC_NOTHING",
                    Set.of("bill_no"),
                    "ALLOC_FACTORY_MISMATCH",
                    Set.of("factory"),
                    "ALLOC_QTY_EXCEEDED",
                    Set.of("qty", "pending"));

    private GenerationErrors() {}

    public static BusinessException preview(String reason) {
        return error(ErrorCodeConstants.PREVIEW_CHANGED, reason, Map.of());
    }

    public static BusinessException stale(String pi, long expected, long actual) {
        return error(
                ErrorCodeConstants.PREVIEW_CHANGED,
                "GEN_PREVIEW_STALE",
                Map.of(
                        "pi",
                        pi,
                        "expected",
                        Long.toString(expected),
                        "actual",
                        Long.toString(actual)));
    }

    public static BusinessException error(
            ErrorCode code, String reason, Map<String, String> params) {
        var values = new HashMap<>(params);
        values.put("reason", reason);
        return new BusinessException(code, safe(code.code(), values));
    }

    /** 只允许与受控错误码对应的完整参数集，其他模块/未知原因/多余字段不进入业务历史。 */
    public static Map<String, String> safe(Integer code, Map<String, String> params) {
        String reason = params.get("reason");
        var keys = PARAMETERS.get(reason == null ? "" : reason);
        if (keys == null || code == null) return Map.of();
        boolean valid =
                code == ErrorCodeConstants.PREVIEW_CHANGED.code()
                                && reason.startsWith("GEN_PREVIEW_")
                        || code == ErrorCodeConstants.QUOTA_EXCEEDED.code()
                                && reason.equals("GEN_QUOTA_EXCEEDED")
                        || code == ErrorCodeConstants.GENERATION_NOT_READY.code()
                                && reason.equals("GEN_QUOTA_EMPTY")
                        || code == ErrorCodeConstants.ALLOCATION_CHANGED.code()
                                && Set.of("ALLOC_NOTHING", "ALLOC_FACTORY_MISMATCH")
                                        .contains(reason)
                        || code == ErrorCodeConstants.ALLOCATION_QTY_EXCEEDED.code()
                                && reason.equals("ALLOC_QTY_EXCEEDED");
        if (!valid || !params.keySet().containsAll(keys)) return Map.of();
        Map<String, String> result = new TreeMap<>();
        result.put("reason", reason);
        for (String key : keys) {
            String value = params.get(key);
            if (value == null
                    || value.length() > 128
                    || value.codePoints().anyMatch(Character::isISOControl)) return Map.of();
            if (Set.of("expected", "actual", "qty", "quota", "pending").contains(key)
                    && !value.matches("[0-9]{1,19}")) return Map.of();
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    public static Map<String, String> read(ObjectMapper json, Integer code, String value) {
        if (value == null) return Map.of();
        var node = json.readTree(value);
        if (!node.isObject()) return Map.of();
        Map<String, String> fields = new HashMap<>();
        for (var entry : node.properties()) {
            if (!entry.getValue().isString()) return Map.of();
            fields.put(entry.getKey(), entry.getValue().asString());
        }
        return safe(code, fields);
    }
}
