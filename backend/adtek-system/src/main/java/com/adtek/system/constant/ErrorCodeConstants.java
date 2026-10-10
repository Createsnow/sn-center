package com.adtek.system.constant;

import com.adtek.common.core.ErrorCode;

/** system 区间：1002000000—1002999999。错误密码不暴露账号状态，密码正确时明确提示停用。 */
public final class ErrorCodeConstants {
    public static final ErrorCode LOGIN_FAILED =
            new ErrorCode(1_002_000_001, 401, "system.loginFailed");
    public static final ErrorCode PASSWORD_WEAK =
            new ErrorCode(1_002_000_002, 400, "system.passwordWeak");
    public static final ErrorCode PASSWORD_WRONG =
            new ErrorCode(1_002_000_003, 400, "system.passwordWrong");
    public static final ErrorCode PASSWORD_SAME =
            new ErrorCode(1_002_000_004, 400, "system.passwordSame");
    public static final ErrorCode ACCOUNT_CHANGED =
            new ErrorCode(1_002_000_005, 401, "system.accountChanged");
    public static final ErrorCode ALREADY_INITIALIZED =
            new ErrorCode(1_002_000_006, 409, "system.alreadyInitialized");

    public static final ErrorCode ACCOUNT_DISABLED =
            new ErrorCode(1_002_000_018, 401, "system.accountDisabled");

    private ErrorCodeConstants() {}

    public static final ErrorCode USERNAME_EXISTS =
            new ErrorCode(1_002_000_007, 409, "system.usernameExists");
    public static final ErrorCode USER_CONFLICT =
            new ErrorCode(1_002_000_008, 409, "system.userConflict");
    public static final ErrorCode SELF_PROTECTION =
            new ErrorCode(1_002_000_009, 409, "system.selfProtection");
    public static final ErrorCode LAST_ADMIN =
            new ErrorCode(1_002_000_010, 409, "system.lastAdmin");
    public static final ErrorCode GRANT_EXCEEDED =
            new ErrorCode(1_002_000_011, 403, "system.grantExceeded");
    public static final ErrorCode CONFIG_CONFLICT =
            new ErrorCode(1_002_000_012, 409, "system.configConflict");
    public static final ErrorCode CONFIG_DUPLICATE =
            new ErrorCode(1_002_000_013, 409, "system.configDuplicate");
    public static final ErrorCode CONFIG_REFERENCE =
            new ErrorCode(1_002_000_014, 409, "system.configReference");
    public static final ErrorCode CONFIG_LIMIT =
            new ErrorCode(1_002_000_015, 409, "system.configLimit");
    public static final ErrorCode AUDIT_OPTIONS_LIMIT =
            new ErrorCode(1_002_000_016, 409, "system.auditOptionsLimit");
    public static final ErrorCode LOGIN_LOCKED =
            new ErrorCode(1_002_000_017, 429, "system.loginLocked");
}
