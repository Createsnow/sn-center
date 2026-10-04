package com.adtek.sn.dto;

import jakarta.validation.constraints.*;

/** 转厂确认前的只读起号测算；不创建转厂任务，也不保存用户选择。 */
public record TransferHintDTO(
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String factoryId,
        @NotBlank @Size(max = 128) String pi,
        @NotBlank @Pattern(regexp = "PI|MATERIAL|SINGLE") String scope,
        @Size(max = 128) String material,
        @Size(max = 64) String sn,
        @NotBlank @Size(max = 128) String toPi,
        @Size(max = 64) String toCustomer) {}
