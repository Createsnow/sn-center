package com.adtek.sn.dto;
import jakarta.validation.constraints.*;
/** expectedQuantity只用于陈旧页面检查，必须等于全部当前待分配数量；没有任意目标工厂或部分数量字段。 */
public record AllocationDTO(@NotBlank String operationId,@NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String billId,
 @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String expectedQuantity) {}
