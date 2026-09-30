package com.adtek.framework.security;

import com.adtek.common.core.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 标准 Security 编解码器的受控装配。只允许 HS256，并校验 issuer/audience/时间及身份声明。
 * 验签成功仍不表示授权：调用方必须继续执行 Redis 会话及 MySQL 当前权限校验。
 */
public final class JwtTokenCodec {
    private final String issuer;
    private final String audience;
    private final Duration accessLifetime;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtTokenCodec(String secretHex, String issuer, String audience, Duration accessLifetime, Clock clock) {
        if (secretHex == null || !secretHex.matches("[0-9a-fA-F]{64}|[0-9a-fA-F]{128}")
                || issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || accessLifetime == null || accessLifetime.compareTo(Duration.ofMinutes(1)) < 0
                || accessLifetime.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Invalid JWT configuration; a 256/512-bit key and 1-30 minute lifetime are required");
        }
        this.issuer = issuer;
        this.audience = audience;
        this.accessLifetime = accessLifetime;
        this.clock = Objects.requireNonNull(clock);
        var key = new SecretKeySpec(HexFormat.of().parseHex(secretHex), "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        var nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        timestamps.setAllowEmptyNotBeforeClaim(false);
        OAuth2TokenValidator<Jwt> claims = token -> validIdentityClaims(token)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer), claims));
        this.decoder = nimbus;
    }

    /** 为已通过登录/刷新的当前主体颁发短时 access token；权限不写入 JWT，避免离线复用旧范围。 */
    public IssuedToken issue(Actor actor, String sessionId) {
        if (sessionId == null || !sessionId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Valid session ID required");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(accessLifetime);
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(Long.toString(actor.userId())).issuedAt(now).notBefore(now).expiresAt(expiresAt)
                .claim("sid", sessionId).claim("sv", Long.toString(actor.securityVersion())).claim("pt",actor.principalType()).build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }

    public JwtDecoder decoder() { return decoder; }

    private boolean validIdentityClaims(Jwt token) {
        Object sid = token.getClaims().get("sid");
        Object version = token.getClaims().get("sv");
        return token.getAudience() != null && token.getAudience().contains(audience)
                && positiveLong(token.getSubject()) && version instanceof String versionText && positiveLong(versionText)
                && sid instanceof String sidText && sidText.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }

    private static boolean positiveLong(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) { return false; }
        try { return Long.parseLong(value) > 0; }
        catch (NumberFormatException error) { return false; }
    }

    public record IssuedToken(String accessToken, Instant expiresAt) {
        @Override public String toString() { return "IssuedToken[redacted]"; }
    }
}
