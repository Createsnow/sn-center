package com.adtek.integration.k3;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * K3只读连接配置；默认不启用，不借用旧工程.env。
 *
 * @param account 来源账套身份，不是租户
 * @param connectSeconds 连接超时秒数
 * @param loginSeconds 登录完整响应超时秒数
 * @param querySeconds 单页/单据完整响应超时秒数
 * @param budgetSeconds 一次完整读取含登录的总秒数预算
 * @param pageSize 受控ERP分页大小；到达最大页/行限制仍有数据则整体拒绝
 * @param responseBytes 单次响应最大字节，流订阅时强制限制
 */
@Validated
@ConfigurationProperties("adtek.k3")
public record K3Settings(
        boolean enabled,
        String url,
        String account,
        String appId,
        String appSecret,
        String username,
        @Min(1) int lcid,
        @Min(1) @Max(60) int connectSeconds,
        @Min(1) @Max(60) int loginSeconds,
        @Min(1) @Max(120) int querySeconds,
        @Min(1) @Max(600) int budgetSeconds,
        @Min(1) @Max(2000) int pageSize,
        @Min(1) @Max(1000) int maxPages,
        @Min(1) @Max(100000) int maxRows,
        @Min(1024) @Max(16777216) int responseBytes) {
    @Override
    public String toString() {
        return "K3Settings[redacted]";
    }
}
