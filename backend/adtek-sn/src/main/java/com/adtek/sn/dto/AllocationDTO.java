package com.adtek.sn.dto;

import jakarta.validation.constraints.*;

/** expectedQuantity核对全部待分配快照；quantity只分配最早的指定数量，省略时保持原整单行为。 */
public record AllocationDTO(
        @NotBlank String operationId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String billId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedQuantity,
        @Pattern(regexp = "[1-9][0-9]{0,18}") String quantity,
        @Size(max = 64) String factoryCode) {}
