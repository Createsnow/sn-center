package com.adtek.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import com.adtek.common.core.RateLimitSubject;

@Schema(description = "工号登录；账号和可信来源分别限流，密码不进入日志。")
public record LoginCommandDTO(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,32}") String username,
        @NotBlank @Size(max = 256) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password) implements RateLimitSubject {
    @Override public String rateLimitSubject() { return username; }
    @Override public String toString() { return "LoginCommandDTO[redacted]"; }
}
