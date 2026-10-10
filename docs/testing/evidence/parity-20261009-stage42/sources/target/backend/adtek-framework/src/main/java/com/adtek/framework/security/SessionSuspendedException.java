package com.adtek.framework.security;

import com.adtek.common.core.ErrorCode;

/** 暂时停用的会话拒绝；HTTP边界清理浏览器Cookie，服务端未过期会话保留。 */
public final class SessionSuspendedException extends RuntimeException {
    private final ErrorCode error;

    public SessionSuspendedException(ErrorCode error) {
        super(error.message());
        this.error = error;
    }

    public ErrorCode error() {
        return error;
    }
}
