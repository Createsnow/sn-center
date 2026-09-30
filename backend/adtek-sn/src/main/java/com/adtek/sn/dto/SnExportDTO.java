package com.adtek.sn.dto;

import com.adtek.sn.query.SnItemQuery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 固定筛选快照和格式组成幂等摘要；同一操作号不得更换格式或筛选。 */
public record SnExportDTO(
        @NotBlank String operationId,
        @NotBlank String format,
        @NotNull @Valid SnItemQuery filters) {}
