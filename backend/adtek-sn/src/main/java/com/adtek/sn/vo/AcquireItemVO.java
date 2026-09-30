package com.adtek.sn.vo;

/** ID/计数/流水统一精确字符串，未知流水为空而不是零。 */
@io.swagger.v3.oas.annotations.media.Schema(
        requiredProperties = {
            "ordinal",
            "snapshot",
            "currentStatus",
            "currentFactoryId",
            "currentPi",
            "currentBatchId",
            "currentMember"
        })
public record AcquireItemVO(
        String ordinal,
        com.adtek.sn.service.support.AcquireSnapshot snapshot,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String currentStatus,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String currentFactoryId,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String currentPi,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String currentBatchId,
        boolean currentMember) {}
