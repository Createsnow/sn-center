package com.adtek.sn.dto;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.UUID;

/** 离线来源盘点：版本/schema/原DATETIME时区均显式指定，目录由受控运维入口提供；源库永不写入。 */
public record LegacySnapshotCommandDTO(
        String runId,
        String sourceSchema,
        String schemaVersion,
        String sourceZone,
        long rowLimit,
        long byteLimit,
        Path outputDirectory) {
    public LegacySnapshotCommandDTO {
        if (!UUID.fromString(runId).toString().equals(runId)
                || sourceSchema == null
                || !sourceSchema.matches("[a-z][a-z0-9_]{0,63}")
                || !"sn-python-repository-v1".equals(schemaVersion)
                || sourceZone == null
                || outputDirectory == null
                || rowLimit < 1
                || rowLimit > 150000000
                || byteLimit < 1024
                || byteLimit > 1099511627776L)
            throw new IllegalArgumentException("LEGACY_SNAPSHOT_INPUT");
        ZoneId.of(sourceZone);
    }
}
