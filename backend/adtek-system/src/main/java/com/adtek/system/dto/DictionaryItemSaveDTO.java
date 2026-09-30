package com.adtek.system.dto;
import jakarta.validation.constraints.*;

/** 新建时 version 为空，修改必须提供原版本；服务不接受客户端分配 ID。 */
public record DictionaryItemSaveDTO(@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String typeId,
        @NotBlank @Size(max = 100) String value,
        @NotBlank @Size(max = 100) String labelZh,
        @NotBlank @Size(max = 100) String labelEn,
        @NotBlank @Size(max = 100) String labelVi,
        @NotNull @Min(0) @Max(99999) Integer sortOrder,
        @NotNull Boolean enabled,
        @Pattern(regexp = "[1-9][0-9]{0,18}") String version) {}
