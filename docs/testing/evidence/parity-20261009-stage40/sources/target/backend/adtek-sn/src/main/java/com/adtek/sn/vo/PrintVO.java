package com.adtek.sn.vo;

/** ID/计数/流水统一精确字符串，未知流水为空而不是零。 */
@io.swagger.v3.oas.annotations.media.Schema(
        requiredProperties = {
            "id",
            "printNo",
            "actorName",
            "factoryId",
            "factoryCode",
            "factoryName",
            "pi",
            "quantity",
            "actorId",
            "operationId",
            "csvTaskId",
            "createdAt",
            "xlsxState",
            "csvState"
        })
public record PrintVO(
        String id,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String printNo,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String actorName,
        String factoryId,
        String factoryCode,
        String factoryName,
        String pi,
        String quantity,
        String actorId,
        String operationId,
        String csvTaskId,
        java.time.Instant createdAt,
        String xlsxState,
        String csvState) {}
