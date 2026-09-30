package com.adtek.sn.vo;
/** ID/计数/流水统一精确字符串，未知流水为空而不是零。 */
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"id","factoryId","factoryCode","factoryName","pi","quantity","actorId","origin","operationId","createdAt"})
public record AcquireBatchVO(String id,String factoryId,String factoryCode,String factoryName,String pi,String quantity,String actorId,String origin,String operationId,java.time.Instant createdAt) {}
