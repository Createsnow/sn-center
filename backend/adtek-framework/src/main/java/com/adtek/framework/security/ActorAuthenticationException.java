package com.adtek.framework.security;

import com.adtek.common.core.ErrorCode;

import org.springframework.security.core.AuthenticationException;

/** 将会话/授权依赖故障保留为 503，而不被 Resource Server 一律改写成 401。 */
public final class ActorAuthenticationException extends AuthenticationException {
    private final ErrorCode error;

    public ActorAuthenticationException(ErrorCode error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public ErrorCode error() {
        return error;
    }
}
