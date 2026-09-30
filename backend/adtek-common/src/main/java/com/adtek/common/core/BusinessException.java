package com.adtek.common.core;

import java.util.Map;

/** 可向客户端公开的业务失败；非预期异常由 Web 边界脱敏为内部错误。 */
public final class BusinessException extends RuntimeException {
    private final ErrorCode error;
    private final Map<String, String> params;

    public BusinessException(ErrorCode error) {
        this(error, Map.of());
    }

    public BusinessException(ErrorCode error, Map<String, String> params) {
        super(error.message());
        this.error = error;
        this.params = Map.copyOf(params);
    }

    public ErrorCode getError() { return error; }
    public Map<String, String> getParams() { return params; }
}
