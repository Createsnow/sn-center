package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * @param ownerId 最近保存设置的实际主体；每次执行重新校验当前权限，未配置为空。
 * @param lastBills 最近成功的单据量，失败不会覆盖；必须结合lastState展示，不能冒充本次成功。
 * @param lastRows 最近成功的分录量，首次成功前为空。
 */
@Schema(
        requiredProperties = {
            "enabled",
            "workerEnabled",
            "intervalMinutes",
            "nextRunAt",
            "ownerId",
            "lastRunId",
            "lastRunAt",
            "lastState",
            "lastErrorCode",
            "lastBills",
            "lastRows",
            "updatedAt"
        })
public record OrderScheduleVO(
        boolean enabled,
        boolean workerEnabled,
        int intervalMinutes,
        @Schema(nullable = true) Instant nextRunAt,
        @Schema(nullable = true) String ownerId,
        @Schema(nullable = true) String lastRunId,
        @Schema(nullable = true) Instant lastRunAt,
        @Schema(nullable = true) String lastState,
        @Schema(nullable = true) Integer lastErrorCode,
        @Schema(nullable = true) String lastBills,
        @Schema(nullable = true) String lastRows,
        @Schema(nullable = true) Instant updatedAt) {}
