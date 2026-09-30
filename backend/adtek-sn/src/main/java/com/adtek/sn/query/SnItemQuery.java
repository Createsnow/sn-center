package com.adtek.sn.query;
import jakarta.validation.constraints.*;
/** 业务过滤不含游标；EMPTY明确筛空物料，piAll仅同PI跨厂只读。 */
public record SnItemQuery(String factoryId,@Size(max=128) String pi,@Size(max=64) String customer,String materialMode,
 @Size(max=128) String material,String status,@Size(max=64) String sn,@Size(max=64) String billNo,String batchId,Boolean piAll) {
 public SnItemQuery { if(piAll==null)piAll=false;if(materialMode==null)materialMode="ALL"; }
}
