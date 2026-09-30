package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 同步记录与发布状态；失败保留旧有效版本，不把FETCHING当成功。
 *
 * @param publishedVersion 本次成功发布的精确版本，失败/进行中为空
 * @param orgSyncFailed 全量订单拉取成功但附带组织拉取失败，沿用原映射并提示
 */
@Schema(
        requiredProperties = {
            "id",
            "mode",
            "billNo",
            "state",
            "baseVersion",
            "publishedVersion",
            "rowsCount",
            "billsCount",
            "errorCode",
            "orgSyncFailed",
            "startedAt",
            "finishedAt"
        })
public record OrderSyncVO(
        String id,
        String mode,
        String billNo,
        String state,
        String baseVersion,
        @Schema(nullable = true) String publishedVersion,
        String rowsCount,
        String billsCount,
        @Schema(nullable = true) Integer errorCode,
        boolean orgSyncFailed,
        Instant startedAt,
        @Schema(nullable = true) Instant finishedAt) {}
