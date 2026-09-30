package com.adtek.framework.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Validated
@ConfigurationProperties("adtek.rate-limit")
public record RateLimitSettings(@NotEmpty Map<String, @Valid Budget> policies) {
    public record Budget(
            @jakarta.validation.constraints.NotBlank String path,
            @Min(1) @Max(10000) int subjectLimit,
            @Min(1) @Max(10000) int sourceLimit,
            @Min(1) @Max(3600) int windowSeconds) {}
}
