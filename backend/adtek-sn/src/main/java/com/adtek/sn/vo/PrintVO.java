package com.adtek.sn.vo;
/** ID/计数/流水统一精确字符串，未知流水为空而不是零。 */
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"id","factoryId","factoryCode","factoryName","pi","quantity","actorId","operationId","csvTaskId","createdAt","xlsxState","csvState"})
public record PrintVO(String id,String factoryId,String factoryCode,String factoryName,String pi,String quantity,String actorId,String operationId,String csvTaskId,java.time.Instant createdAt,String xlsxState,String csvState) {}
