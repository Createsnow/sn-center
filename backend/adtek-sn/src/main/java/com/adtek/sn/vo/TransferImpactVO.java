package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 全厂转厂读取才可测算目标PI水位；目录权限不扩大库存范围。提示按读取时规则/成员计算，并非执行承诺。 */
@Schema(requiredProperties = {"available", "ruleCode", "ruleSource", "before", "after", "unused"})
public record TransferImpactVO(
        boolean available,
        String ruleCode,
        String ruleSource,
        @Schema(nullable = true) NextSequenceVO before,
        @Schema(nullable = true) NextSequenceVO after,
        @Schema(nullable = true) String unused) {}
