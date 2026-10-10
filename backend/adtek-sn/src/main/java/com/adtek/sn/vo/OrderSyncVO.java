package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 同步记录与发布状态；失败保留旧有效版本，不把FETCHING当成功。
 *
 * @param publishedVersion 本次成功发布的精确版本，失败/进行中为空
 * @param orgSyncFailed 附带工厂读取或发布失败的诊断标志，页面仍显示订单结果及未建档组织
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
