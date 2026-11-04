package com.adtek.equipment.dto;

import jakarta.validation.constraints.*;

/** 原版本必须以十进制文本提交；旧表单不能覆盖新修改。 */
public record EquipmentUpdateDTO(
        @NotBlank @Size(max = 128) String name,
        @NotNull Boolean enabled,
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String version) {}
