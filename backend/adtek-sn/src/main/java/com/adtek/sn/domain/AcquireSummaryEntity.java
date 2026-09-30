package com.adtek.sn.domain;

/** 持久事实或显式SQL投影；不直接作为HTTP响应。 */
public record AcquireSummaryEntity(
        long factoryId,
        String factoryCode,
        String factoryName,
        String pi,
        String customer,
        long toAcquire,
        long toPrint,
        long applying) {}
