package com.adtek.common.core;

import java.util.Map;

/** JSON 统一信封。code 固定 int32；业务 ID、流水及大计数由各 VO 声明为 String。 */
public record Result<T>(int code, String message, T data, Map<String, String> params, String traceId) {
    public Result {
        params = Map.copyOf(params);
    }

    public static <T> Result<T> success(T data, String traceId) {
        return new Result<>(0, "common.success", data, Map.of(), traceId);
    }

    public static Result<Void> failure(ErrorCode error, Map<String, String> params, String traceId) {
        return new Result<>(error.code(), error.message(), null, params, traceId);
    }
}
