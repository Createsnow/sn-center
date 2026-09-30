package com.adtek.common.constant;

import com.adtek.common.core.ErrorCode;

/** common 区间：1000000000—1000999999。模块业务错误不得放入此类。 */
public final class ErrorCodeConstants {
    public static final ErrorCode INVALID_ARGUMENT = new ErrorCode(1_000_000_001, 400, "common.invalidArgument");
    public static final ErrorCode INTERNAL_ERROR = new ErrorCode(1_000_000_002, 500, "common.internalError");
    public static final ErrorCode UNAUTHENTICATED = new ErrorCode(1_000_000_003, 401, "common.unauthenticated");
    public static final ErrorCode FORBIDDEN = new ErrorCode(1_000_000_004, 403, "common.forbidden");
    public static final ErrorCode NOT_FOUND = new ErrorCode(1_000_000_005, 404, "common.notFound");
    public static final ErrorCode DEPENDENCY_UNAVAILABLE = new ErrorCode(1_000_000_006, 503, "common.dependencyUnavailable");
    public static final ErrorCode METHOD_NOT_ALLOWED = new ErrorCode(1_000_000_007, 405, "common.methodNotAllowed");
    public static final ErrorCode CONFLICT_RETRY = new ErrorCode(1_000_000_008, 409, "common.conflictRetry");
    private ErrorCodeConstants() {}
}
