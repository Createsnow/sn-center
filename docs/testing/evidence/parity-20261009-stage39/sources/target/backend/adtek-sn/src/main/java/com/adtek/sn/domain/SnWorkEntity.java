package com.adtek.sn.domain;

import java.time.Instant;

/** 固定命令输入和业务结果；任务成功提交前的PENDING不表示SN已经生效。 */
public record SnWorkEntity(
        String taskId,
        String kind,
        String pi,
        Long billId,
        Long factoryId,
        long actorId,
        String payload,
        String state,
        long quantity,
        Long startSeq,
        Long endSeq,
        Long decodedQty,
        Long maxSeq,
        Long lastSeq,
        String firstSn,
        String lastSn,
        String fileName,
        Integer errorCode,
        Instant createdAt,
        Instant finishedAt,
        String actorName,
        String errorParams) {}
