package com.adtek.sn.domain;

/** 持久事实或显式SQL投影；不直接作为HTTP响应。 */
public record AcquireMemberEntity(
        String batchId,
        long ordinal,
        java.time.LocalDate genMonth,
        String itemId,
        String sn,
        String seqPi,
        Long seqDec,
        String snapshot) {}
