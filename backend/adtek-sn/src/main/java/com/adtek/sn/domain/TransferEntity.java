package com.adtek.sn.domain;

import java.time.Instant;

/** 头部展示在创建时固定；仅状态/版本/处理人/意见/处理时间随合法命令推进。 */
public record TransferEntity(
        String id,
        String mode,
        String status,
        long version,
        long fromFactoryId,
        String fromFactoryCode,
        String fromFactoryName,
        String fromPi,
        String scope,
        String material,
        String sn,
        long toFactoryId,
        String toFactoryCode,
        String toFactoryName,
        String toPi,
        String toCustomer,
        String toMaterial,
        String targetSource,
        String reason,
        long quantity,
        long actorId,
        Long decidedBy,
        String note,
        Instant createdAt,
        Instant decidedAt) {}
