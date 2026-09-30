package com.adtek.framework.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 秘密必须外部注入，禁止打印配置对象；本地 HTTP 测试的 Cookie 例外由显式 local profile 控制。 */
@Validated
@ConfigurationProperties("adtek.security")
public record SecuritySettings(
        @NotBlank String issuer, @NotBlank String audience,
        @Pattern(regexp = "[0-9a-fA-F]{64}|[0-9a-fA-F]{128}") @NotBlank String jwtSecret,
        @Min(1) @Max(30) int accessMinutes,
        @Min(1) @Max(168) int refreshHours,
        boolean cookieSecure) {
    @Override public String toString() { return "SecuritySettings[redacted]"; }
}
