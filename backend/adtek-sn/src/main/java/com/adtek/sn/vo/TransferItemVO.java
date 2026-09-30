package com.adtek.sn.vo;

@io.swagger.v3.oas.annotations.media.Schema(
        requiredProperties = {
            "ordinal",
            "snapshot",
            "fromStatus",
            "fromBatchId",
            "fromPrintId",
            "currentStatus",
            "currentFactoryId",
            "currentPi",
            "currentBatchId"
        })
public record TransferItemVO(
        String ordinal,
        com.adtek.sn.service.support.AcquireSnapshot snapshot,
        String fromStatus,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String fromBatchId,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String fromPrintId,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String currentStatus,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String currentFactoryId,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String currentPi,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String currentBatchId) {}
