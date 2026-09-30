package com.adtek.sn.domain;
/** 持久事实或显式SQL投影；不直接作为HTTP响应。 */
public record AcquireBatchEntity(String id,long factoryId,String factoryCode,String factoryName,String pi,long quantity,long actorId,String origin,String operationId,java.time.Instant createdAt) {}
