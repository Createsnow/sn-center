package com.adtek.sn.domain;

import java.time.Instant;

/** 文件格式与固定快照一起保存；工作器恢复时不采用当前页面的格式选择。 */
public record SnAuditExportEntity(
        String taskId,
        long actorId,
        String format,
        String filters,
        long quantity,
        Instant createdAt,
        int formatVersion) {}
