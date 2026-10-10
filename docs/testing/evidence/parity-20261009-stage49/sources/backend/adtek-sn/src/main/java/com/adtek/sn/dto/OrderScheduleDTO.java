package com.adtek.sn.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;

/**
 * @param intervalMinutes 全量同步间隔，分钟；未提供/null沿用当前间隔，业务范围在用例中校验并留痕。
 */
public record OrderScheduleDTO(
        @NotNull Boolean enabled, @Schema(types = {"integer", "null"}) Integer intervalMinutes) {}
