package com.adtek.sn.domain;

/** 固定转厂成员与当前挂靠的只读投影；来源批次号来自不可变批次头。 */
public record TransferDetailEntity(
        long ordinal,
        String sn,
        String seqText,
        Long seqDec,
        String seqPi,
        String fromStatus,
        String fromBatchNo,
        String currentStatus,
        Long currentFactoryId,
        String currentFactoryCode,
        String currentFactoryName,
        String currentPi) {}
