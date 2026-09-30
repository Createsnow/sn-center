package com.adtek.sn.domain;

/** 持久事实或显式SQL投影；不直接作为HTTP响应。 */
public record AcquireSegmentEntity(
        long factoryId,
        String factoryCode,
        String pi,
        String material,
        String status,
        String seqPi,
        Long startSeq,
        Long endSeq,
        String startSn,
        String endSn,
        long quantity,
        String groupId) {}
