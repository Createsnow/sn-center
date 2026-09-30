package com.adtek.system.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.util.List;

/** 新建账号的输入白名单；密码只用于哈希，不进入审计、VO 或 toString。 */
public record UserCreateDTO(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,32}") String username,
        @NotBlank @Size(max = 100) String displayName,
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String deptId,
        @NotNull @Size(max = 20) List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> roleIds,
        @Size(max = 100) List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> factoryIds,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max = 256) String password) {
    @Override public String toString() { return "UserCreateDTO[redacted]"; }
}
