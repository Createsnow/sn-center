package com.adtek.sn.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.adtek.sn.query.SnAuditQuery;
public record SnAuditExportDTO(@NotBlank String operationId,@NotNull @Pattern(regexp="CSV|XLSX") String format,@NotNull @Valid SnAuditQuery filters) {}
