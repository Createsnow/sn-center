package com.adtek.system.dto;

import jakarta.validation.constraints.*;

/** 新建时 version 为空，修改必须提供原版本；服务不接受客户端分配 ID。 */
public record DictionaryTypeSaveDTO(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,63}") String code,
        @NotBlank @Size(max = 100) String name,
        @NotNull Boolean enabled,
        @Pattern(regexp = "[1-9][0-9]{0,18}") String version) {}
