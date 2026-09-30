package com.adtek.framework.security;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.constant.ErrorCodeConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis TIME 统一多实例时钟；同毫秒请求用随机 member 区分，不覆盖并发计数。 */
@Component
public class RedisRateLimitService implements RateLimitService {
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local window = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
                local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
                return math.max(1, tonumber(first[2]) + window - now)
            end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public RedisRateLimitService(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void require(String action, String subject, int maximum, Duration window) {
        if (action == null || !action.matches("[a-z][a-z0-9:._-]{1,80}") || subject == null || subject.isBlank()
                || maximum < 1 || maximum > 10_000 || window == null || window.toMillis() < 1
                || window.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid rate-limit policy or subject");
        }
        try {
            Long retryMillis = redis.execute(CHECK, List.of("adtek:rate:" + action + ":" + hash(subject)),
                    Long.toString(window.toMillis()), Integer.toString(maximum), UUID.randomUUID().toString());
            if (retryMillis == null) { throw new BusinessException(ErrorCodeConstants.DEPENDENCY_UNAVAILABLE); }
            if (retryMillis > 0) {
                throw new BusinessException(ErrorCodeConstants.RATE_LIMITED,
                        Map.of("retryAfter", Long.toString(Math.max(1, (retryMillis + 999) / 1000))));
            }
        } catch (DataAccessException error) {
            throw new BusinessException(ErrorCodeConstants.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static String hash(String subject) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
}
