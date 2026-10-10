package com.adtek.sn.service.support;

import static com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT;
import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.core.BusinessException;
import com.adtek.common.core.ErrorCode;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** 初始化公开错误参数白名单；行号和容量保留精确十进制，历史不保存异常原文。 */
public final class InitializationErrors {
    private record Definition(ErrorCode code, Set<String> keys) {}

    private static final Map<String, Definition> DEFINITIONS =
            Map.of(
                    "PI_INIT_EMPTY", new Definition(INVALID_ARGUMENT, Set.of()),
                    "PI_IMPORT_SN_INVALID", new Definition(INVALID_ARGUMENT, Set.of("line")),
                    "PI_IMPORT_DUP_IN_LIST", new Definition(SN_CONFLICT, Set.of("sn")),
                    "PI_IMPORT_DUP_EXISTING", new Definition(SN_CONFLICT, Set.of("sn", "pi")),
                    "PI_IMPORT_SEQ_DUP", new Definition(SN_CONFLICT, Set.of("seq", "pi")),
                    "PI_IMPORT_TOO_LARGE", new Definition(RESOURCE_LIMIT, Set.of("max")),
                    "PI_INIT_LOCKED", new Definition(PI_INIT_LOCKED, Set.of("pi")),
                    "GEN_START_INVALID", new Definition(SEQUENCE_OVERFLOW, Set.of("max")));

    private InitializationErrors() {}

    public static BusinessException error(String reason, Map<String, String> params) {
        var definition = DEFINITIONS.get(reason);
        if (definition == null) throw new IllegalArgumentException("Unknown initialization reason");
        var fields = new HashMap<>(params);
        fields.put("reason", reason);
        return new BusinessException(definition.code(), safe(definition.code().code(), fields));
    }

    public static BusinessException locked(String pi) {
        return error("PI_INIT_LOCKED", Map.of("pi", pi));
    }

    public static BusinessException start(long max) {
        return error("GEN_START_INVALID", Map.of("max", Long.toString(max)));
    }

    /** 参数必须属于当前数字码和原因；缺失或非法值整体舍弃，额外字段不进入结果。 */
    public static Map<String, String> safe(Integer code, Map<String, String> params) {
        String reason = params.get("reason");
        var definition = DEFINITIONS.get(reason == null ? "" : reason);
        if (definition == null || code == null || code != definition.code().code()) return Map.of();
        Map<String, String> result = new TreeMap<>();
        result.put("reason", reason);
        for (String key : definition.keys()) {
            String value = params.get(key);
            if (value == null
                    || value.length() > 128
                    || value.codePoints().anyMatch(Character::isISOControl)) return Map.of();
            if (Set.of("line", "max").contains(key) && !value.matches("[1-9][0-9]{0,18}"))
                return Map.of();
            if (key.equals("seq") && !value.matches("0|[1-9][0-9]{0,18}")) return Map.of();
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
