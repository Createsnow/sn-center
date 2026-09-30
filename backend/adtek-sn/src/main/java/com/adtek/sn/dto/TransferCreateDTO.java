package com.adtek.sn.dto;
import jakarta.validation.constraints.*;
/** 空客户/物料沿用每枚原值；MATERIAL范围的空字符串明确表示空物料。 */
public record TransferCreateDTO(@NotBlank String operationId,@NotBlank String factoryId,@NotBlank @Size(max=128) String pi,
 @NotBlank String scope,@Size(max=128) String material,@Size(max=64) String sn,
 @NotBlank String toFactoryId,@NotBlank @Size(max=128) String toPi,@Size(max=64) String toCustomer,@Size(max=128) String toMaterial,
 @NotBlank String targetSource,@NotBlank @Size(max=500) String reason) {}
