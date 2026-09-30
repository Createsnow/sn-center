package com.adtek.framework.task.domain;

import com.adtek.framework.task.FileState;

import java.time.Instant;

/** 文件键由服务器生成；摘要/字节数只有发布后提交READY才可信，恢复保持固定快照和标识。 */
public record FileEntity(
        String id,
        String taskId,
        FileState state,
        String storageKey,
        String sha256,
        Long byteSize,
        long rowCount,
        Instant createdAt,
        String filename,
        String mediaType) {
    public FileEntity(
            String id,
            String taskId,
            FileState state,
            String storageKey,
            String sha256,
            Long byteSize,
            long rowCount,
            Instant createdAt) {
        this(id, taskId, state, storageKey, sha256, byteSize, rowCount, createdAt, null, null);
    }
}
