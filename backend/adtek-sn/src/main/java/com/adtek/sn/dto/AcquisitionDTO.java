package com.adtek.sn.dto;

import jakarta.validation.constraints.*;

/** 命令范围始终是工厂+PI，不提供物料或数量字段。 */
public record AcquisitionDTO(
        @NotBlank String operationId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String factoryId,
        @NotBlank @Size(max = 128) String pi) {}
