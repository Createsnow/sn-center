package com.adtek.sn.dto;

import com.adtek.sn.query.SnAuditQuery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record SnAuditExportDTO(
        @NotBlank String operationId,
        @NotNull @Pattern(regexp = "CSV|XLSX") String format,
        @NotNull @Valid SnAuditQuery filters) {}
