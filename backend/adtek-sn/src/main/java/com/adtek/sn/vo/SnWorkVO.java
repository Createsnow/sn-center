package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 不暴露固定输入中的历史SN清单；任务进度另查，SUCCESS的业务结果才是已提交事实。 */
@Schema(
        requiredProperties = {
            "taskId",
            "kind",
            "pi",
            "billId",
            "factoryId",
            "factoryCode",
            "actorId",
            "state",
            "quantity",
            "startSeq",
            "endSeq",
            "decodedQty",
            "maxSeq",
            "lastSeq",
            "firstSn",
            "lastSn",
            "fileName",
            "errorCode",
            "createdAt",
            "finishedAt"
        })
public record SnWorkVO(
        String taskId,
        String kind,
        String pi,
        @Schema(nullable = true) String billId,
        @Schema(nullable = true) String factoryId,
        String factoryCode,
        String actorId,
        String state,
        String quantity,
        @Schema(nullable = true) String startSeq,
        @Schema(nullable = true) String endSeq,
        @Schema(nullable = true) String decodedQty,
        @Schema(nullable = true) String maxSeq,
        @Schema(nullable = true) String lastSeq,
        @Schema(nullable = true) String firstSn,
        @Schema(nullable = true) String lastSn,
        String fileName,
        @Schema(nullable = true) Integer errorCode,
        Instant createdAt,
        @Schema(nullable = true) Instant finishedAt) {}
