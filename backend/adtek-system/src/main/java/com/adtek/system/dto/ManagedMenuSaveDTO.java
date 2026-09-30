package com.adtek.system.dto;

import jakarta.validation.constraints.*;

/** 新建时 version 为空，修改必须提供原版本；服务不接受客户端分配 ID。 */
public record ManagedMenuSaveDTO(
        @NotBlank @Size(max = 100) String name,
        @Pattern(regexp = "[1-9][0-9]{0,18}") String parentId,
        @NotNull @Pattern(regexp = "[MCF]") String menuType,
        @Size(max = 200) String permission,
        @Size(max = 200) String path,
        @Size(max = 200) String component,
        @NotNull @Min(0) @Max(99999) Integer sortOrder,
        @NotNull Boolean enabled,
        @Pattern(regexp = "[1-9][0-9]{0,18}") String version) {}
