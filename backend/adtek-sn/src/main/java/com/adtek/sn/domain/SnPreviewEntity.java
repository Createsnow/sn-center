package com.adtek.sn.domain;

import java.time.Instant;

/** 不占水位和额度；taskId一旦绑定即永久消耗该预演。 */
public record SnPreviewEntity(
        String id,
        long actorId,
        String pi,
        long billId,
        String payload,
        Instant expiresAt,
        String taskId,
        Instant createdAt) {}
