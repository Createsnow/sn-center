package com.adtek.sn.dto;
import jakarta.validation.constraints.*;
/** 原操作号重放不再次审批；新意图须提交当前转厂版本。 */
public record TransferDecisionDTO(@NotBlank String operationId,@NotBlank String version,@Size(max=500) String note) {}
