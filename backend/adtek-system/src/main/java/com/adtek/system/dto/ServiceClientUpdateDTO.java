package com.adtek.system.dto;

import jakarta.validation.constraints.*;

/** 乐观版本保护轮换/停用；停用和范围调整都撤销旧会话。 */
public record ServiceClientUpdateDTO(
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String version,
        boolean enabled,
        @NotNull @jakarta.validation.Valid ServiceClientCreateDTO grant) {}
