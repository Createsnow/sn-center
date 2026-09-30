package com.adtek.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 仅供受控初始化命令使用，不绑定 HTTP；秘密不得输出到日志。 */
public record BootstrapAdminDTO(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,32}") String username,
        @NotBlank @Size(max = 64) String password,
        @NotBlank @Size(max = 100) String displayName) {
    @Override public String toString() { return "BootstrapAdminDTO[REDACTED]"; }
}
