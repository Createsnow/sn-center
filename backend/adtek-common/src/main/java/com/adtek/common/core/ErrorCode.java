package com.adtek.common.core;

/** 稳定错误标识；message 是前端翻译键，参数不包含 SQL、凭据或堆栈。 */
public record ErrorCode(int code, int httpStatus, String message) {
    public ErrorCode {
        if (code < 1_000_000_000 || httpStatus < 400 || httpStatus > 599
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Invalid business error definition");
        }
    }
}
