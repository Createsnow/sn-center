package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 起号建议不修改水位；超过规则容量时startSn为空，startSeq仍保留精确十进制。 */
@Schema(requiredProperties = {"ownLastSeq", "startSeq", "startSn", "adjusted", "bySeq", "bySn"})
public record NextSequenceVO(
        String ownLastSeq,
        String startSeq,
        @Schema(nullable = true) String startSn,
        boolean adjusted,
        @Schema(nullable = true) String bySeq,
        @Schema(nullable = true) String bySn) {}
