package com.adtek.sn.domain;

import java.time.Instant;

/** 单行调度设置和最近运行快照；lastRunId作为发布栅栏，leaseUntil按数据库时钟判断。 */
public record OrderScheduleEntity(
        boolean enabled,
        int intervalMinutes,
        Instant nextRunAt,
        Long ownerId,
        String lastRunId,
        Instant lastRunAt,
        Instant leaseUntil,
        String lastState,
        Integer lastErrorCode,
        Long lastBills,
        Long lastRows,
        Instant updatedAt) {}
