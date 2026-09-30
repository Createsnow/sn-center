package com.adtek.framework.security;

import com.adtek.common.core.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.assertj.core.api.Assertions.*;

class JwtTokenCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-29T05:00:00Z");
    private final String key = HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
    private final Actor actor = new Actor(9_007_199_254_740_993L, 7, false, Map.of());

    private JwtTokenCodec codec(String secret, String issuer, String audience, Instant now) {
        return new JwtTokenCodec(secret, issuer, audience, Duration.ofMinutes(15), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void validatesStandardThreePartTokenWithoutEmbeddingPermissions() {
        var codec = codec(key, "issuer", "audience", NOW);
        String sid = UUID.randomUUID().toString();
        var issued = codec.issue(actor, sid);
        assertThat(issued.accessToken().split("\\.")).hasSize(3);
        var jwt = codec.decoder().decode(issued.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("9007199254740993");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sid);
        assertThat(jwt.getClaimAsString("sv")).isEqualTo("7");
        assertThat(jwt.getClaims()).doesNotContainKeys("permissions", "roles");
        assertThat(issued.toString()).doesNotContain(issued.accessToken());
    }

    @Test
    void rejectsExpiredFutureIssuerAudienceAndSignatureMismatch() {
        String token = codec(key, "issuer", "audience", NOW).issue(actor, UUID.randomUUID().toString()).accessToken();
        assertThatThrownBy(() -> codec(key, "issuer", "audience", NOW.plusSeconds(901)).decoder().decode(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> codec(key, "issuer", "audience", NOW.minusSeconds(1)).decoder().decode(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> codec(key, "other", "audience", NOW).decoder().decode(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> codec(key, "issuer", "other", NOW).decoder().decode(token)).isInstanceOf(JwtException.class);
        String otherKey = HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
        assertThatThrownBy(() -> codec(otherKey, "issuer", "audience", NOW).decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWeakKeysAndLegacyTwoPartTokens() {
        assertThatThrownBy(() -> codec("00", "issuer", "audience", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec(key, "issuer", "audience", NOW).decoder().decode("legacy.payload")).isInstanceOf(JwtException.class);
    }
}
