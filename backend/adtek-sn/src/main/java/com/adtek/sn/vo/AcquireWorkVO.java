package com.adtek.sn.vo;
/** ID/计数/流水统一精确字符串，未知流水为空而不是零。 */
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"taskId","kind","factoryId","pi","origin","operationId","state","quantity","eligible","blocked","updatedQty","alreadyQty","batchId","errorCode","createdAt","finishedAt"})
public record AcquireWorkVO(String taskId,String kind,String factoryId,String pi,String origin,String operationId,String state,String quantity,String eligible,String blocked,String updatedQty,String alreadyQty,@io.swagger.v3.oas.annotations.media.Schema(nullable=true) String batchId,@io.swagger.v3.oas.annotations.media.Schema(nullable=true) Integer errorCode,java.time.Instant createdAt,@io.swagger.v3.oas.annotations.media.Schema(nullable=true) java.time.Instant finishedAt) {}
