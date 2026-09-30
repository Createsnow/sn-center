package com.adtek.sn.query;
import jakarta.validation.constraints.*;
/** 物料ALL/EMPTY/VALUE互斥；跨厂模式仅同PI只读，不能用于批次/文件授权。 */
public record AcquireQuery(String factoryId,@Size(max=128) String pi,String materialMode,@Size(max=128) String material,String status,boolean piAll,
 @Min(1) int page,@Min(1) @Max(100) int size) {}
