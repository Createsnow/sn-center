package com.adtek.sn.domain;

import java.time.*;

/** 完整旧查询投影；按不可变gen_month/id游标，工厂为空的待分配仅全厂读取可见。 */
public record SnQueryItemEntity(
        LocalDate genMonth,
        String id,
        String sn,
        String pi,
        String seqPi,
        Long seqDec,
        Long ruleVersionId,
        String customer,
        String material,
        Long factoryId,
        String factoryCode,
        String factoryName,
        String status,
        String source,
        String billNo,
        String sourceSystem,
        String sourceAccount,
        String sourceBillId,
        String sourceEntryId,
        String currentBatchId,
        String currentPrintId,
        String currentTransferId,
        Instant createdAt,
        Instant acquiredAt,
        Instant printedAt,
        String currentBatchNo,
        long creationOrder) {}
