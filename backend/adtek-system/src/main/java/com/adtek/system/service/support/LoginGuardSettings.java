package com.adtek.system.service.support;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 登录失败窗口和锁定时间；默认五次失败后锁十五分钟，独立于入口请求速率预算。 */
@Validated
@ConfigurationProperties("adtek.security.login-guard")
public record LoginGuardSettings(
        @DefaultValue("5") @Min(1) @Max(100) int maximumFailures,
        @DefaultValue("900") @Min(1) @Max(86400) int lockSeconds) {}
