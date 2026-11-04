package com.adtek.equipment.dto;

import jakarta.validation.constraints.*;

/** 编码唯一且创建后不可更改；接口不接受所有者、部门及授权范围。 */
public record EquipmentCreateDTO(
        @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String code,
        @NotBlank @Size(max = 128) String name,
        @NotNull Boolean enabled) {}
