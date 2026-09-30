package com.adtek.sn.vo;

/** ID/计数/流水统一精确字符串，未知流水为空而不是零。 */
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"ordinal", "sn", "outcome"})
public record AcquireResultVO(String ordinal, String sn, String outcome) {}
