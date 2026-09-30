package com.adtek.framework.security;

import com.adtek.common.core.BusinessException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisFailureTest {
    @Test
    void sessionDependencyFailureIsNotMisreportedAsExpiredOrAllowed() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenThrow(new RedisConnectionFailureException("simulated unavailable dependency"));
        var store = new RedisSessionStore(redis);
        assertThatThrownBy(() -> store.requireActive(UUID.randomUUID().toString(), 1, 1))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getError().httpStatus()).isEqualTo(503);
                    assertThat(error.getError().code()).isEqualTo(1_003_000_002);
                });
    }

    @Test
    void malformedRefreshCredentialNeverAccessesRedis() {
        var redis = mock(StringRedisTemplate.class);
        var store = new RedisSessionStore(redis);
        assertThatThrownBy(() -> store.rotate("not-a-credential", Duration.ofMinutes(5)))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getError().httpStatus()).isEqualTo(401));
        verifyNoInteractions(redis);
    }
}
