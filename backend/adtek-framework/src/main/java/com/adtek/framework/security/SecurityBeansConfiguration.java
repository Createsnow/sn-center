package com.adtek.framework.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** 标准加密组件装配；无默认密钥，也不注册 Boot 的默认内存账号。 */
@Configuration
@EnableConfigurationProperties(SecuritySettings.class)
public class SecurityBeansConfiguration {
    @Bean
    JwtTokenCodec jwtTokenCodec(SecuritySettings settings, Environment environment) {
        if (!settings.cookieSecure() && !environment.acceptsProfiles(Profiles.of("local", "integration-test"))) {
            throw new IllegalArgumentException("Insecure auth cookies are restricted to explicit local/integration-test profiles");
        }
        if (!settings.cookieSecure() && !java.util.Set.of("127.0.0.1", "::1").contains(environment.getProperty("server.address"))) {
            throw new IllegalArgumentException("Local HTTP auth must bind to a loopback address");
        }
        return new JwtTokenCodec(settings.jwtSecret(), settings.issuer(), settings.audience(),
                Duration.ofMinutes(settings.accessMinutes()), Clock.systemUTC());
    }

    @Bean JwtDecoder jwtDecoder(JwtTokenCodec codec) { return codec.decoder(); }

    @Bean
    PasswordEncoder passwordEncoder() {
        var encoder = new Pbkdf2PasswordEncoder("", 16, 600_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        // 仅接受迁移工具显式标记的旧算法；新建/改密仍一律600k，不猜测无前缀或未知格式。
        var previous = new Pbkdf2PasswordEncoder("", 16, 120_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder("pbkdf2-sha256-600k", Map.of("pbkdf2-sha256-600k", encoder,"pbkdf2-sha256-120k",previous));
    }
}
