package com.adtek.sn.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/** 清单与范围二选一，完整SN保持原大小写；集合去重后一次确认。 */
public record PrintConfirmationDTO(
        @NotBlank String operationId,
        @NotBlank String batchId,
        @NotBlank String targetStatus,
        @Size(max = 100000) List<@NotBlank @Size(max = 64) String> sns,
        @Size(max = 1000) List<@Valid Range> ranges) {
    public record Range(
            @NotBlank @Size(max = 64) String startSn, @NotBlank @Size(max = 64) String endSn) {}
}
