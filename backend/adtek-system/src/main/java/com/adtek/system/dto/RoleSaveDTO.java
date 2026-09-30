package com.adtek.system.dto;

import jakarta.validation.constraints.*;

import java.util.List;

/** 新建时 version 为空，修改必须提供原版本；服务不接受客户端分配 ID。 */
public record RoleSaveDTO(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,63}") String code,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(1) @Max(5) Integer dataScope,
        @NotNull @Min(1) @Max(2) Integer factoryScope,
        @NotNull @Size(max = 100)
                List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> menuIds,
        @NotNull @Size(max = 100)
                List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> deptIds,
        @NotNull Boolean enabled,
        @Pattern(regexp = "[1-9][0-9]{0,18}") String version) {}
