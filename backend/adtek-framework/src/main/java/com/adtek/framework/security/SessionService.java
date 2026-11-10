package com.adtek.framework.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 服务端会话（MySQL {@code framework_session}）与访问令牌（HS256 JWT，只携带会话号、账户、口令版本）。
 *
 * <p>JWT、会话行与 {@link PrincipalPort} 当前授权三者同时有效才放行。页面会话（{@link Kind#PAGE}）签发时吊销该账户之前的页面会话（同一账户
 * 只在最后登录的页面有效，被挤掉的页面收到 SESSION_REPLACED）；对接方会话（{@link Kind#API}）互不影响。改密、重置时由业务调用 {@link #revokeAll}
 * 吊销全部会话。读请求不写会话表。
 *
 * <p>事务：{@link #issue} 与 {@link #revokeAll} 加入调用方事务（与登录留痕、改密同一事务提交）。
 */
@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String REASON_REPLACED = "REPLACED";
    public static final String REASON_PASSWORD = "PASSWORD";
    public static final String REASON_LOGOUT = "LOGOUT";

    /** 会话种类。 */
    public enum Kind {
        PAGE,
        API
    }

    /** 已签发的令牌。 */
    public record Issued(String token, String sessionId, Instant expiresAt) {}

    /** 校验通过的会话。 */
    public record Session(
            String id,
            long userId,
            Kind kind,
            long passwordVersion,
            String revokeReason,
            boolean expired) {}

    private final JdbcTemplate jdbc;
    private final SecurityProperties properties;
    private final byte[] key;

    public SessionService(JdbcTemplate jdbc, SecurityProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.key = deriveKey(properties.effectiveSecret());
    }

    static byte[] deriveKey(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("adtek-session-jwt:" + secret).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public long ttlSeconds() {
        return properties.tokenTtlSeconds();
    }

    /** 签发会话与令牌；页面会话先吊销该账户之前的页面会话。 */
    public Issued issue(long userId, long passwordVersion, Kind kind) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        if (kind == Kind.PAGE) {
            jdbc.update(
                    "UPDATE framework_session SET revoked_at = ?, revoke_reason = ? WHERE user_id = ?"
                            + " AND kind = 'PAGE' AND revoked_at IS NULL",
                    Timestamp.valueOf(now),
                    REASON_REPLACED,
                    userId);
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String id = HexFormat.of().formatHex(raw);
        LocalDateTime expires = now.plusSeconds(properties.tokenTtlSeconds());
        jdbc.update(
                "INSERT INTO framework_session(id, user_id, kind, pwd_version, created_at, expires_at)"
                        + " VALUES (?,?,?,?,?,?)",
                id,
                userId,
                kind.name(),
                passwordVersion,
                Timestamp.valueOf(now),
                Timestamp.valueOf(expires));
        Instant exp = expires.toInstant(ZoneOffset.UTC);
        return new Issued(sign(id, userId, passwordVersion, kind, exp), id, exp);
    }

    private String sign(String id, long userId, long passwordVersion, Kind kind, Instant exp) {
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .jwtID(id)
                        .subject(Long.toString(userId))
                        .claim("pv", passwordVersion)
                        .claim("kind", kind.name())
                        .expirationTime(Date.from(exp))
                        .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign session token", e);
        }
    }

    /** 校验令牌签名、有效期与会话行；任何一项不符返回空（调用方按 TOKEN_INVALID 处理），已吊销的会话带吊销原因返回。 */
    public Optional<Session> verify(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || !jwt.verify(new MACVerifier(key))) {
                return Optional.empty();
            }
        } catch (ParseException | JOSEException | IllegalStateException e) {
            return Optional.empty();
        }
        String id;
        long userId;
        long pv;
        Kind kind;
        try {
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(Instant.now())) {
                return Optional.empty();
            }
            id = claims.getJWTID();
            userId = Long.parseLong(claims.getSubject());
            pv = claims.getLongClaim("pv");
            kind = Kind.valueOf(claims.getStringClaim("kind"));
        } catch (ParseException | RuntimeException e) {
            return Optional.empty();
        }
        List<Session> rows =
                jdbc.query(
                        "SELECT id, user_id, kind, pwd_version, revoke_reason,"
                                + " expires_at < UTC_TIMESTAMP() AS expired"
                                + " FROM framework_session WHERE id = ?",
                        (rs, n) ->
                                new Session(
                                        rs.getString("id"),
                                        rs.getLong("user_id"),
                                        Kind.valueOf(rs.getString("kind")),
                                        rs.getLong("pwd_version"),
                                        rs.getString("revoke_reason"),
                                        rs.getBoolean("expired")),
                        id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Session session = rows.getFirst();
        if (session.userId() != userId
                || session.passwordVersion() != pv
                || session.kind() != kind
                || session.expired()) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** 吊销账户的全部会话（改密、重置密码 / 密钥）。 */
    public int revokeAll(long userId, String reason) {
        return jdbc.update(
                "UPDATE framework_session SET revoked_at = UTC_TIMESTAMP(), revoke_reason = ?"
                        + " WHERE user_id = ? AND revoked_at IS NULL",
                reason,
                userId);
    }

    /** 吊销一个会话（退出登录）。 */
    public void revoke(String sessionId, String reason) {
        jdbc.update(
                "UPDATE framework_session SET revoked_at = UTC_TIMESTAMP(), revoke_reason = ?"
                        + " WHERE id = ? AND revoked_at IS NULL",
                reason,
                sessionId);
    }

    /** 分批清理过期超过 1 天的会话；返回删除行数。 */
    public int purgeExpired() {
        int total = 0;
        while (true) {
            int n =
                    jdbc.update(
                            "DELETE FROM framework_session WHERE expires_at <"
                                    + " DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY) LIMIT 5000");
            total += n;
            if (n < 5000) {
                break;
            }
        }
        if (total > 0) {
            log.info("Purged {} expired sessions", total);
        }
        return total;
    }
}
