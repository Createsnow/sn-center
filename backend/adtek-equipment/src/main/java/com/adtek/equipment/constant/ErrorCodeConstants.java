package com.adtek.equipment.constant;

import com.adtek.common.core.ErrorCode;

/** 样板业务独占 1004 号段；复制新模块时必须重新分配并通过错误码目录检查。 */
public final class ErrorCodeConstants {
    public static final ErrorCode CODE_EXISTS =
            new ErrorCode(1_004_000_001, 409, "equipment.codeExists");
    public static final ErrorCode VERSION_CHANGED =
            new ErrorCode(1_004_000_002, 409, "equipment.versionChanged");
    public static final ErrorCode DEPARTMENT_REQUIRED =
            new ErrorCode(1_004_000_003, 409, "equipment.departmentRequired");

    private ErrorCodeConstants() {}
}
