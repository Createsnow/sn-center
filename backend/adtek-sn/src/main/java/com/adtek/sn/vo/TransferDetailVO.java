package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 页面按固定成员分页；状态含来源单厂视角的 TRANSFERRED_OUT，不改变当前库存。 */
@Schema(
        requiredProperties = {
            "ordinal",
            "sn",
            "seqText",
            "seqDec",
            "seqPi",
            "fromStatus",
            "fromBatchNo",
            "currentStatus",
            "currentFactoryId",
            "currentFactoryCode",
            "currentFactoryName",
            "currentPi"
        })
public record TransferDetailVO(
        String ordinal,
        String sn,
        @Schema(nullable = true) String seqText,
        @Schema(nullable = true) String seqDec,
        @Schema(nullable = true) String seqPi,
        String fromStatus,
        @Schema(nullable = true) String fromBatchNo,
        @Schema(nullable = true) String currentStatus,
        @Schema(nullable = true) String currentFactoryId,
        @Schema(nullable = true) String currentFactoryCode,
        @Schema(nullable = true) String currentFactoryName,
        @Schema(nullable = true) String currentPi) {}
