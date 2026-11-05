package com.adtek.common.constant;

import com.adtek.common.core.ErrorCode;

/** 公共错误码（号段 1000）：协议、认证、授权、并发与依赖等与具体业务无关的失败。 */
public enum CommonErrorCode implements ErrorCode {
    INVALID_REQUEST(1_000_000_001, "error.common.invalidRequest", 400),
    UNAUTHENTICATED(1_000_000_002, "error.common.unauthenticated", 401),
    FORBIDDEN(1_000_000_003, "error.common.forbidden", 403),
    NOT_FOUND(1_000_000_004, "error.common.notFound", 404),
    METHOD_NOT_ALLOWED(1_000_000_005, "error.common.methodNotAllowed", 405),
    CONFLICT(1_000_000_006, "error.common.conflict", 409),
    PAYLOAD_TOO_LARGE(1_000_000_007, "error.common.payloadTooLarge", 413),
    UNSUPPORTED_MEDIA_TYPE(1_000_000_008, "error.common.unsupportedMediaType", 415),
    TOO_MANY_REQUESTS(1_000_000_009, "error.common.tooManyRequests", 429),
    INTERNAL_ERROR(1_000_000_010, "error.common.internalError", 500),
    DEPENDENCY_UNAVAILABLE(1_000_000_011, "error.common.dependencyUnavailable", 503);

    private final int code;
    private final String key;
    private final int httpStatus;

    CommonErrorCode(int code, String key, int httpStatus) {
        this.code = code;
        this.key = key;
        this.httpStatus = httpStatus;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
