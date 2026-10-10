package com.adtek.sn.domain;

import java.time.Instant;

/** 查询导出版本与固定行一起保存，恢复不依赖当前页面的导出格式。 */
public record SnQueryExportEntity(
        String taskId,
        long actorId,
        String format,
        String filters,
        long quantity,
        Instant createdAt,
        int formatVersion) {}
