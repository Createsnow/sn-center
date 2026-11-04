package com.adtek.system.service.support;

import com.adtek.common.core.BusinessException;
import com.adtek.system.constant.ErrorCodeConstants;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 按工号跨实例累计失败；Redis TIME 和 Lua 原子更新，应用重启不会清零。 只保存工号摘要及计数/时间，不保存密码或账号存在性；未知账号执行相同策略。 */
@Component
@EnableConfigurationProperties(LoginGuardSettings.class)
public class LoginFailureGuard {
    private static final DefaultRedisScript<Long> UPDATE =
            new DefaultRedisScript<>(
                    """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local window = tonumber(ARGV[2])
            local locked = tonumber(redis.call('HGET', KEYS[1], 'locked') or '0')
            if locked > now then return locked - now end
            if ARGV[1] == 'check' then return 0 end
            if ARGV[1] == 'success' then
                redis.call('DEL', KEYS[1])
                return 0
            end
            local first = tonumber(redis.call('HGET', KEYS[1], 'first') or '0')
            if first == 0 or now - first >= window then
                redis.call('HSET', KEYS[1], 'first', now, 'count', 0, 'locked', 0)
                redis.call('PEXPIRE', KEYS[1], window)
            end
            local count = redis.call('HINCRBY', KEYS[1], 'count', 1)
            if count >= tonumber(ARGV[3]) then
                redis.call('HSET', KEYS[1], 'locked', now + window)
                redis.call('PEXPIRE', KEYS[1], window)
                return -1
            end
            return 0
            """,
                    Long.class);
    private final StringRedisTemplate redis;
    private final LoginGuardSettings settings;

    public LoginFailureGuard(StringRedisTemplate redis, LoginGuardSettings settings) {
        this.redis = redis;
        this.settings = settings;
    }

    /** 校验锁定状态；锁定中正确密码也不能绕过，依赖故障拒绝登录。 */
    public void check(String username) {
        update(username, "check");
    }

    /** 累计一次失败；触发阈值的这次仍返回原凭据错误，返回值只用于登记锁定事件。 */
    public boolean failed(String username) {
        return update(username, "fail") == -1;
    }

    /** 成功发会话之前清零；原子复核期间新形成的锁，迟到的正确密码不能解锁。 */
    public void succeeded(String username) {
        update(username, "success");
    }

    private long update(String username, String action) {
        try {
            Long result =
                    redis.execute(
                            UPDATE,
                            List.of(key(username)),
                            action,
                            Long.toString(settings.lockSeconds() * 1000L),
                            Integer.toString(settings.maximumFailures()));
            if (result == null) throw unavailable();
            if (result > 0)
                throw new BusinessException(
                        ErrorCodeConstants.LOGIN_LOCKED,
                        Map.of(
                                "minutes",
                                Long.toString((result + 59999) / 60000),
                                "retryAfter",
                                Long.toString((result + 999) / 1000)));
            return result;
        } catch (DataAccessException error) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                com.adtek.framework.constant.ErrorCodeConstants.DEPENDENCY_UNAVAILABLE);
    }

    private static String key(String username) {
        try {
            return "adtek:login-failure:"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(username.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
