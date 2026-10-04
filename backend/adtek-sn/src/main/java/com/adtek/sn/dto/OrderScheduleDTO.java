package com.adtek.sn.dto;

import jakarta.validation.constraints.*;

/**
 * @param intervalMinutes 全量同步间隔，分钟；改间隔/开启从数据库当前时间重新计时。
 */
public record OrderScheduleDTO(
        @NotNull Boolean enabled, @Min(10) @Max(10080) int intervalMinutes) {}
