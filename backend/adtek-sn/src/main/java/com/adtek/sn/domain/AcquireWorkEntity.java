package com.adtek.sn.domain;
/** 持久事实或显式SQL投影；不直接作为HTTP响应。 */
public record AcquireWorkEntity(String taskId,String kind,long factoryId,String pi,long actorId,String origin,String operationId,String payload,String state,long quantity,long eligible,long blocked,long updatedQty,long alreadyQty,String batchId,Integer errorCode,java.time.Instant createdAt,java.time.Instant finishedAt) {}
