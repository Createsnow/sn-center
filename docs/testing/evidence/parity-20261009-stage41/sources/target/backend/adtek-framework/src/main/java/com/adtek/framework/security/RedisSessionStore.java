package com.adtek.framework.security;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.constant.ErrorCodeConstants;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 原子 Lua 同时写会话与 TTL；刷新比较/轮换处于同一 Redis 原子操作内。 */
@Component
public class RedisSessionStore implements SessionStore {
    private static final Pattern SID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern SECRET = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> CREATE =
            new DefaultRedisScript<>(
                    """
                    if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
                    redis.call('HSET', KEYS[1], 'uid', ARGV[1], 'version', ARGV[2], 'refresh', ARGV[3])
                    redis.call('PEXPIRE', KEYS[1], ARGV[4])
                    return 1
                    """,
                    Long.class);
    private static final DefaultRedisScript<String> ROTATE =
            new DefaultRedisScript<>(
                    """
                    local saved = redis.call('HGET', KEYS[1], 'refresh')
                    if not saved then return '' end
                    if saved ~= ARGV[1] then redis.call('DEL', KEYS[1]); return '' end
                    local uid = redis.call('HGET', KEYS[1], 'uid')
                    local version = redis.call('HGET', KEYS[1], 'version')
                    if not uid or not version then redis.call('DEL', KEYS[1]); return '' end
                    redis.call('HSET', KEYS[1], 'refresh', ARGV[2])
                    redis.call('PEXPIRE', KEYS[1], ARGV[3])
                    return uid .. ':' .. version
                    """,
                    String.class);
    private final StringRedisTemplate redis;

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public CreatedSession create(long userId, long credentialVersion, Duration lifetime) {
        validateLifetime(lifetime);
        if (userId <= 0 || credentialVersion <= 0) {
            throw new IllegalArgumentException("Invalid session identity");
        }
        String sid = UUID.randomUUID().toString();
        String secret = secret();
        try {
            Long created =
                    redis.execute(
                            CREATE,
                            List.of(key(sid)),
                            Long.toString(userId),
                            Long.toString(credentialVersion),
                            digest(secret),
                            Long.toString(lifetime.toMillis()));
            if (!Long.valueOf(1).equals(created)) {
                throw new BusinessException(ErrorCodeConstants.DEPENDENCY_UNAVAILABLE);
            }
            return new CreatedSession(sid, sid + "." + secret);
        } catch (DataAccessException error) {
            throw unavailable();
        }
    }

    @Override
    public void requireActive(String sessionId, long userId, long credentialVersion) {
        requireSessionId(sessionId);
        try {
            List<Object> values =
                    redis.opsForHash().multiGet(key(sessionId), List.of("uid", "version"));
            if (values.size() != 2
                    || !Long.toString(userId).equals(values.get(0))
                    || !Long.toString(credentialVersion).equals(values.get(1))) {
                throw invalid();
            }
        } catch (DataAccessException error) {
            throw unavailable();
        }
    }

    @Override
    public RotatedSession rotate(String credential, Duration lifetime) {
        validateLifetime(lifetime);
        if (credential == null || credential.length() != 101) {
            throw invalid();
        }
        String sid = credential.substring(0, 36);
        String oldSecret = credential.substring(37);
        requireSessionId(sid);
        if (credential.charAt(36) != '.' || !SECRET.matcher(oldSecret).matches()) {
            throw invalid();
        }
        String replacement = secret();
        try {
            String value =
                    redis.execute(
                            ROTATE,
                            List.of(key(sid)),
                            digest(oldSecret),
                            digest(replacement),
                            Long.toString(lifetime.toMillis()));
            if (value == null || value.isEmpty()) {
                throw invalid();
            }
            String[] parts = value.split(":", -1);
            if (parts.length != 2) {
                throw invalid();
            }
            return new RotatedSession(
                    sid,
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    sid + "." + replacement);
        } catch (DataAccessException error) {
            throw unavailable();
        } catch (NumberFormatException error) {
            throw invalid();
        }
    }

    @Override
    public void revoke(String sessionId) {
        requireSessionId(sessionId);
        try {
            redis.delete(key(sessionId));
        } catch (DataAccessException error) {
            throw unavailable();
        }
    }

    private static void validateLifetime(Duration lifetime) {
        if (lifetime == null
                || lifetime.isNegative()
                || lifetime.isZero()
                || lifetime.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Session lifetime must be within 7 days");
        }
    }

    private static void requireSessionId(String sid) {
        if (sid == null || !SID.matcher(sid).matches()) {
            throw invalid();
        }
    }

    private static String key(String sid) {
        return "adtek:session:" + sid;
    }

    private static String secret() {
        return HexFormat.of().formatHex(RANDOM.generateSeed(32));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCodeConstants.SESSION_INVALID);
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCodeConstants.DEPENDENCY_UNAVAILABLE);
    }
}
