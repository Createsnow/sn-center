package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 原型首页生成任务投影；订单取提交时快照，执行进度与已提交数量分开。 */
@Schema(
        requiredProperties = {
            "id",
            "taskId",
            "billNo",
            "pi",
            "factoryCode",
            "quantity",
            "processed",
            "state",
            "createdAt"
        })
public record DashboardJobVO(
        String id,
        String taskId,
        String billNo,
        String pi,
        String factoryCode,
        String quantity,
        String processed,
        String state,
        Instant createdAt) {}
