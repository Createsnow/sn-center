package com.adtek.sn.domain;

import java.time.Instant;

public record TransferWorkEntity(
        String taskId,
        String kind,
        String transferId,
        long fromFactoryId,
        String fromPi,
        String toPi,
        String payload,
        String state,
        Integer errorCode,
        Instant createdAt,
        Instant finishedAt) {}
