package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 所有水位/数量和操作者ID为精确字符串，未初始化时返回全零控制视图。 */
@Schema(
        requiredProperties = {
            "pi",
            "lastSeq",
            "generatedQty",
            "importedQty",
            "startSeq",
            "startLocked",
            "lockedBy",
            "lockedAt",
            "activeTaskId"
        })
public record PiCounterVO(
        String pi,
        String lastSeq,
        String generatedQty,
        String importedQty,
        @Schema(nullable = true) String startSeq,
        boolean startLocked,
        @Schema(nullable = true) String lockedBy,
        @Schema(nullable = true) Instant lockedAt,
        @Schema(nullable = true) String activeTaskId) {}
