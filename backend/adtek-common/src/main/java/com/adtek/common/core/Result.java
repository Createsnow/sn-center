package com.adtek.common.core;

import java.util.Map;
import java.util.Objects;

/**
 * 统一响应信封：成功时 {@code code=0}、{@code key="success"} 并携带 {@code data}；失败时为错误码、翻译键与插值参数，HTTP 状态取自
 * {@link ErrorCode#httpStatus()}。
 *
 * <p>{@code traceId} 由 framework 在写出响应前填入，业务代码只调用 {@link #ok(Object)} 或抛出 {@link
 * BusinessException}。参数只放可公开给 当前用户的值（如字段名、上限），不放秘密、SQL 或堆栈；业务 ID 等精确数值以十进制字符串放入 data。
 *
 * @param code 0 表示成功，否则为 {@link ErrorCode#code()}
 * @param key 翻译键
 * @param args 翻译插值参数，非空且不可变
 * @param data 成功数据；失败时为 null
 * @param traceId 请求追踪号；写出前由 framework 填入
 * @param <T> 数据类型
 */
public record Result<T>(int code, String key, Map<String, String> args, T data, String traceId) {
    public static final int SUCCESS_CODE = 0;
    public static final String SUCCESS_KEY = "success";

    public Result {
        Objects.requireNonNull(key, "key");
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_KEY, Map.of(), data, null);
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static Result<Void> fail(ErrorCode errorCode, Map<String, String> args) {
        return new Result<>(errorCode.code(), errorCode.key(), args, null, null);
    }

    public static Result<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, Map.of());
    }

    /** 返回填入追踪号的副本。 */
    public Result<T> withTraceId(String traceId) {
        return new Result<>(code, key, args, data, traceId);
    }
}
