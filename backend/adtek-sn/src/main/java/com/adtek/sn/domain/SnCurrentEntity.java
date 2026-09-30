package com.adtek.sn.domain;

/** 持久事实或显式SQL投影；不直接作为HTTP响应。 */
public record SnCurrentEntity(
        java.time.LocalDate genMonth,
        String id,
        String sn,
        String pi,
        String seqPi,
        Long seqDec,
        String customer,
        String material,
        Long factoryId,
        String status,
        Long ruleVersionId,
        String sourceSystem,
        String sourceAccount,
        String sourceBillId,
        String sourceEntryId,
        String billNo,
        String sourcePi,
        String sourceOrgId,
        String currentBatchId,
        String currentPrintId,
        String eligibility) {}
