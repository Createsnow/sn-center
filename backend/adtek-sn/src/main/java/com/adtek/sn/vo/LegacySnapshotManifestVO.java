package com.adtek.sn.vo;

import java.util.List;
import java.util.Map;

/** 离线文件协议v1；计数/字节为精确字符串，表文件包含敏感原行，manifest仅含摘要和问题类别。 */
public record LegacySnapshotManifestVO(
        String format,
        String runId,
        String schemaVersion,
        String sourceSqlSha256,
        String sourceSchema,
        String sourceServerUuid,
        String sourceMysqlVersion,
        String sourceZone,
        String capturedAtUtc,
        String schemaDigest,
        String sourceFingerprint,
        List<Table> tables,
        List<Issue> issues,
        List<Map<String, String>> schemaMetadata,
        boolean sourceReadOnly,
        boolean readyForPromotion) {
    public record Table(
            String name,
            String file,
            List<String> columns,
            List<String> primaryKey,
            String rows,
            String bytes,
            String sha256) {}

    public record Issue(String code, String table, String count, String disposition) {}
}
