package com.adtek.framework.security;

import com.adtek.common.annotation.RateLimiter;
import com.adtek.common.core.BusinessException;
import com.adtek.common.core.RateLimitSubject;
import com.adtek.framework.constant.ErrorCodeConstants;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/** 在事务外消耗入口预算；默认只信任 socket remoteAddr，不读取任意 X-Forwarded-For。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@EnableConfigurationProperties(RateLimitSettings.class)
public class RateLimiterAspect {
    private final RateLimitService limiter;
    private final RateLimitSettings settings;

    public RateLimiterAspect(RateLimitService limiter, RateLimitSettings settings) {
        this.limiter = limiter;
        this.settings = settings;
    }

    @Around("@annotation(policy)")
    public Object limit(ProceedingJoinPoint invocation, RateLimiter policy) throws Throwable {
        var budget = settings.policies().get(policy.policy());
        if (budget == null) {
            throw new IllegalStateException("Unregistered rate-limit policy");
        }
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes request)) {
            throw new BusinessException(ErrorCodeConstants.FORBIDDEN);
        }
        Duration window = Duration.ofSeconds(budget.windowSeconds());
        if (!policy.policy()
                .equals(request.getRequest().getAttribute(EntryRateLimitFilter.CHECKED_POLICY))) {
            limiter.require(
                    policy.policy() + ":ip",
                    request.getRequest().getRemoteAddr(),
                    budget.sourceLimit(),
                    window);
        }
        // 无效/缺失匿名主体只计入来源预算，不建立所有匿名请求共用的主体键。
        for (Object argument : invocation.getArgs()) {
            if (argument instanceof RateLimitSubject subject) {
                String key = subject.rateLimitSubject();
                if (key != null && !key.isBlank()) {
                    limiter.require(
                            policy.policy() + ":subject", key, budget.subjectLimit(), window);
                }
            }
        }
        return invocation.proceed();
    }
}
