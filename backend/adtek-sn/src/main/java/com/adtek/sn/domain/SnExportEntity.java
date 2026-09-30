package com.adtek.sn.domain;

import java.time.Instant;

public record SnExportEntity(
        String taskId,
        long actorId,
        String format,
        String filters,
        long quantity,
        Instant createdAt) {}
