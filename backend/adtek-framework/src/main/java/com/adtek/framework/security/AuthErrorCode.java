package com.adtek.framework.security;

import com.adtek.common.core.ErrorCode;

/** 会话认证错误码（号段 1003）：令牌、会话与当前授权失效；登录本身的失败由业务模块定义。 */
public enum AuthErrorCode implements ErrorCode {
    TOKEN_INVALID(1_003_000_001, "error.auth.tokenInvalid", 401, "登录已失效，请重新登录"),
    SESSION_REPLACED(1_003_000_002, "error.auth.sessionReplaced", 401, "该账户已在其他页面登录，本页已退出"),
    PASSWORD_CHANGED(1_003_000_003, "error.auth.passwordChanged", 401, "密码已修改或被重置，请用新密码重新登录"),
    ACCOUNT_DISABLED(1_003_000_004, "error.auth.accountDisabled", 401, "账户已停用"),
    USER_NO_FACTORY(1_003_000_005, "error.auth.userNoFactory", 403, "账户未授权任何生产组织，请联系系统管理员");

    private final int code;
    private final String key;
    private final int httpStatus;
    private final String message;

    AuthErrorCode(int code, String key, int httpStatus, String message) {
        this.code = code;
        this.key = key;
        this.httpStatus = httpStatus;
        this.message = message;
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

    /** 中文说明（日志与审计用；前端按 key 本地化）。 */
    public String message() {
        return message;
    }
}
