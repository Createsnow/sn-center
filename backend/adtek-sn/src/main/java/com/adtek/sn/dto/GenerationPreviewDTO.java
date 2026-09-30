package com.adtek.sn.dto;
import jakarta.validation.constraints.*;
/** qty为来源订单的新增数量；start仅在PI未锁定时允许。物料筛选不进入命令。 */
public record GenerationPreviewDTO(@NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String billId,
 @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String quantity,@Pattern(regexp="[1-9][0-9]{0,18}") String start) {}
