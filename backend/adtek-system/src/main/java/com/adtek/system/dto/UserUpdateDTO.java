package com.adtek.system.dto;

import jakarta.validation.constraints.*;
import java.util.List;

/** 工号为历史身份，不允许在编辑资料时更改；version 防止旧页面覆盖新权限；factoryIds未传保留，空集合清除显式工厂。 */
public record UserUpdateDTO(@NotBlank @Size(max = 100) String displayName,
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String deptId,
        @NotNull @Size(max = 20) List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> roleIds,
        @Size(max = 100) List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> factoryIds,
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String version) {}
