package com.adtek.framework.constant;

import com.adtek.common.core.ErrorCode;

/** framework 区间：1003000000—1003999999。 */
public final class ErrorCodeConstants {
    public static final ErrorCode SESSION_INVALID =
            new ErrorCode(1_003_000_001, 401, "framework.sessionInvalid");
    public static final ErrorCode DEPENDENCY_UNAVAILABLE =
            new ErrorCode(1_003_000_002, 503, "framework.dependencyUnavailable");
    public static final ErrorCode RATE_LIMITED =
            new ErrorCode(1_003_000_003, 429, "framework.rateLimited");
    public static final ErrorCode FORBIDDEN =
            new ErrorCode(1_003_000_004, 403, "framework.forbidden");
    public static final ErrorCode PASSWORD_CHANGE_REQUIRED =
            new ErrorCode(1_003_000_005, 403, "framework.passwordChangeRequired");
    public static final ErrorCode SCOPE_RULE_MISSING =
            new ErrorCode(1_003_000_006, 403, "framework.scopeRuleMissing");
    public static final ErrorCode OPERATION_CONFLICT =
            new ErrorCode(1_003_000_007, 409, "framework.operationConflict");
    public static final ErrorCode TASK_CAPACITY =
            new ErrorCode(1_003_000_008, 409, "framework.taskCapacity");
    public static final ErrorCode TASK_NOT_CANCELLABLE =
            new ErrorCode(1_003_000_009, 409, "framework.taskNotCancellable");
    public static final ErrorCode TASK_BUDGET =
            new ErrorCode(1_003_000_010, 409, "framework.taskBudget");
    public static final ErrorCode TASK_LEASE_LOST =
            new ErrorCode(1_003_000_011, 409, "framework.taskLeaseLost");
    public static final ErrorCode FILE_NOT_READY =
            new ErrorCode(1_003_000_012, 409, "framework.fileNotReady");
    public static final ErrorCode FILE_RECOVERY_REQUIRED =
            new ErrorCode(1_003_000_013, 409, "framework.fileRecoveryRequired");
    public static final ErrorCode RESOURCE_LIMIT =
            new ErrorCode(1_003_000_014, 409, "framework.resourceLimit");

    private ErrorCodeConstants() {}
}
