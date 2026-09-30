package com.adtek.sn.vo;
import java.time.Instant;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
/** 只计算且固定条件，不创建SN；有效期内仍需当前水位/来源/规则全部一致。 */
@Schema(requiredProperties={"id","quantity","baseLastSeq","startSeq","endSeq","firstSn","lastSn","expiresAt","segments"})
public record GenerationPreviewVO(String id,String quantity,String baseLastSeq,String startSeq,String endSeq,String firstSn,String lastSn,Instant expiresAt,List<Segment> segments) {
 @Schema(requiredProperties={"sourceEntryId","ordinal","material","quantity","startSeq","endSeq","firstSn","lastSn"})
 public record Segment(String sourceEntryId,String ordinal,String material,String quantity,String startSeq,String endSeq,String firstSn,String lastSn) {}
}
