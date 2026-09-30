package com.adtek.sn.vo;

@io.swagger.v3.oas.annotations.media.Schema(
        requiredProperties = {"taskId", "format", "quantity", "fileState", "createdAt"})
public record SnExportVO(
        String taskId,
        String format,
        String quantity,
        String fileState,
        java.time.Instant createdAt) {}
