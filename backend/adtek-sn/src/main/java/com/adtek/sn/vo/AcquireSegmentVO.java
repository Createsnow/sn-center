package com.adtek.sn.vo;
/** ID/计数/流水统一精确字符串，未知流水为空而不是零。 */
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"factoryId","factoryCode","pi","material","status","seqPi","startSeq","endSeq","startSn","endSn","quantity","groupId"})
public record AcquireSegmentVO(String factoryId,String factoryCode,String pi,String material,String status,@io.swagger.v3.oas.annotations.media.Schema(nullable=true) String seqPi,@io.swagger.v3.oas.annotations.media.Schema(nullable=true) String startSeq,@io.swagger.v3.oas.annotations.media.Schema(nullable=true) String endSeq,String startSn,String endSn,String quantity,String groupId) {}
