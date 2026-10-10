package com.adtek.sn.constant;

import com.adtek.common.core.ErrorCode;

/** sn 区间：1004000000—1004999999；数字码发布后不得改变业务含义。 */
public final class ErrorCodeConstants {
    public static final ErrorCode RULE_INVALID =
            new ErrorCode(1_004_000_001, 400, "sn.ruleInvalid");
    public static final ErrorCode SEQUENCE_OVERFLOW =
            new ErrorCode(1_004_000_002, 409, "sn.sequenceOverflow");
    public static final ErrorCode FACTORY_EXISTS =
            new ErrorCode(1_004_000_003, 409, "sn.factoryExists");
    public static final ErrorCode MAPPING_CONFLICT =
            new ErrorCode(1_004_000_004, 409, "sn.mappingConflict");
    public static final ErrorCode VERSION_CHANGED =
            new ErrorCode(1_004_000_005, 409, "sn.versionChanged");
    public static final ErrorCode RULE_EXISTS = new ErrorCode(1_004_000_006, 409, "sn.ruleExists");
    public static final ErrorCode SNAPSHOT_CHANGED =
            new ErrorCode(1_004_000_007, 409, "sn.snapshotChanged");
    public static final ErrorCode ERP_NOT_CONFIGURED =
            new ErrorCode(1_004_000_008, 503, "sn.erpNotConfigured");
    public static final ErrorCode ERP_UNAVAILABLE =
            new ErrorCode(1_004_000_009, 503, "sn.erpUnavailable");
    public static final ErrorCode ERP_INVALID = new ErrorCode(1_004_000_010, 502, "sn.erpInvalid");
    public static final ErrorCode RESOURCE_LIMIT =
            new ErrorCode(1_004_000_011, 409, "sn.resourceLimit");
    public static final ErrorCode SYNC_FAILED = new ErrorCode(1_004_000_012, 503, "sn.syncFailed");
    public static final ErrorCode PI_INIT_LOCKED =
            new ErrorCode(1_004_000_013, 409, "sn.piInitLocked");
    public static final ErrorCode PI_BUSY = new ErrorCode(1_004_000_014, 409, "sn.piBusy");
    public static final ErrorCode SN_CONFLICT = new ErrorCode(1_004_000_015, 409, "sn.snConflict");
    public static final ErrorCode PREVIEW_CHANGED =
            new ErrorCode(1_004_000_016, 409, "sn.previewChanged");
    public static final ErrorCode GENERATION_NOT_READY =
            new ErrorCode(1_004_000_017, 409, "sn.generationNotReady");
    public static final ErrorCode QUOTA_EXCEEDED =
            new ErrorCode(1_004_000_018, 409, "sn.quotaExceeded");
    public static final ErrorCode ALLOCATION_CHANGED =
            new ErrorCode(1_004_000_019, 409, "sn.allocationChanged");
    public static final ErrorCode ACQUIRE_CHANGED =
            new ErrorCode(1_004_000_020, 409, "sn.acquireChanged");
    public static final ErrorCode CALLBACK_RANGE_INVALID =
            new ErrorCode(1_004_000_021, 400, "sn.callbackRangeInvalid");
    public static final ErrorCode PRINT_NOTHING =
            new ErrorCode(1_004_000_022, 409, "sn.printNothing");
    public static final ErrorCode TRANSFER_CHANGED =
            new ErrorCode(1_004_000_023, 409, "sn.transferChanged");
    public static final ErrorCode TRANSFER_NOTHING =
            new ErrorCode(1_004_000_024, 409, "sn.transferNothing");
    public static final ErrorCode TRANSFER_NO_CHANGE =
            new ErrorCode(1_004_000_025, 409, "sn.transferNoChange");
    public static final ErrorCode SYNC_EXECUTION_EXPIRED =
            new ErrorCode(1_004_000_026, 409, "sn.syncExecutionExpired");
    public static final ErrorCode RULE_CHOICE_REQUIRED =
            new ErrorCode(1_004_000_027, 409, "sn.ruleChoiceRequired");
    public static final ErrorCode START_BEFORE_INCOMING =
            new ErrorCode(1_004_000_028, 409, "sn.startBeforeIncoming");
    public static final ErrorCode ALLOCATION_QTY_EXCEEDED =
            new ErrorCode(1_004_000_029, 409, "sn.allocationQtyExceeded");

    private ErrorCodeConstants() {}
}
