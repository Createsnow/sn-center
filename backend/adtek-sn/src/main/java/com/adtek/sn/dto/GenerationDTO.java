package com.adtek.sn.dto;
import jakarta.validation.constraints.NotBlank;
/** 参数固定在预演中；同operationId同载荷返回原任务，失败后必须新预演和新操作号。 */
public record GenerationDTO(@NotBlank String operationId,@NotBlank String previewId) {}
