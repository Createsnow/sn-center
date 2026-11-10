package com.adtek.framework.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 安全链配置（server 的 application.yml 绑定）。
 *
 * @param secret 签名密钥（环境变量 SN_SECRET，至少 32 位随机串）；production 环境为空、示例值或过短时拒绝启动
 * @param environment 运行环境（环境变量 ENVIRONMENT，与原系统相同；production 为生产环境）
 * @param tokenTtlSeconds 访问令牌与会话有效期（秒）
 * @param cookieSecure 会话 Cookie 是否带 Secure（只有本机 loopback 的 local profile 可关闭）
 * @param publicPaths 匿名可访问的业务入口（如登录），Ant 风格
 * @param machinePaths 对外机器接口：不走页面会话与 CSRF，由提供方在用例中认证（如 MES 接口），Ant 风格
 */
@ConfigurationProperties("adtek.security")
public record SecurityProperties(
        String secret,
        String environment,
        long tokenTtlSeconds,
        boolean cookieSecure,
        List<String> publicPaths,
        List<String> machinePaths) {
    public static final int MIN_SECRET_LENGTH = 32;
    private static final List<String> WEAK =
            List.of("change-me-sn-center-secret", "change-me-to-a-long-random-string");
    static final String FALLBACK_SECRET = "change-me-sn-center-secret";

    public SecurityProperties {
        secret = secret == null ? "" : secret.strip();
        tokenTtlSeconds = tokenTtlSeconds <= 0 ? 12 * 3600 : tokenTtlSeconds;
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        machinePaths = machinePaths == null ? List.of() : List.copyOf(machinePaths);
    }

    /** 是否生产环境（ENVIRONMENT=production）。 */
    public boolean production() {
        return "production".equals(environment);
    }

    /** 实际使用的密钥：未设置时（仅非生产）用兜底值，与原系统一致。 */
    public String effectiveSecret() {
        return secret.isEmpty() ? FALLBACK_SECRET : secret;
    }

    /** 密钥的问题；null 表示可用。 */
    public String secretProblem() {
        if (secret.isEmpty()) {
            return "SN_SECRET 未设置";
        }
        if (WEAK.contains(secret)) {
            return "SN_SECRET 仍是示例值";
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            return "SN_SECRET 长度不足 " + MIN_SECRET_LENGTH + " 位";
        }
        return null;
    }
}
