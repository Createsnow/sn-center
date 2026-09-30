package com.adtek.sn.vo;

/** ID/计数/流水统一精确字符串，未知流水为空而不是零。 */
@io.swagger.v3.oas.annotations.media.Schema(
        requiredProperties = {
            "id",
            "sn",
            "pi",
            "seqPi",
            "seqDec",
            "seqText",
            "customer",
            "material",
            "factoryId",
            "status",
            "currentBatchId"
        })
public record SnReadItemVO(
        String id,
        String sn,
        String pi,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String seqPi,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String seqDec,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String seqText,
        String customer,
        String material,
        String factoryId,
        String status,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String currentBatchId) {}
