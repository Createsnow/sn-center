package com.adtek.common.core;

import java.time.Instant;

/** 持久任务协议；进度仅供展示，业务完成以最终状态和结果引用为准。 */
public record TaskVO(
        String taskId,
        String operationId,
        String status,
        String completed,
        String total,
        String resultId,
        Instant updatedAt) {}
