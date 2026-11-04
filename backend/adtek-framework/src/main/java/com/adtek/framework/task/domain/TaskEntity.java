package com.adtek.framework.task.domain;

import com.adtek.framework.task.TaskState;

import java.time.Instant;

/** 持久任务控制行；executionEpoch为提交栅栏，leaseUntil不证明旧事务已经结束。 */
public record TaskEntity(
        String id,
        long userId,
        String taskType,
        String inputDigest,
        String inputJson,
        long authorizationVersion,
        TaskState state,
        String owner,
        long executionEpoch,
        Instant leaseUntil,
        int attempt,
        int maxAttempts,
        Instant deadline,
        Instant availableAt,
        String resultRef,
        Integer errorCode,
        Instant createdAt,
        Instant updatedAt) {}
