package com.adtek.framework.security;

import com.adtek.common.core.BusinessException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

/** 在 CSRF/JSON 解析前限制来源预算；主体预算仍由 Service 的 RateLimiter 声明执行。 */
final class EntryRateLimitFilter extends OncePerRequestFilter {
    static final String CHECKED_POLICY = EntryRateLimitFilter.class.getName() + ".policy";
    private final RateLimitService limits;
    private final RateLimitSettings settings;
    private final ObjectMapper mapper;

    EntryRateLimitFilter(RateLimitService limits, RateLimitSettings settings, ObjectMapper mapper) {
        this.limits = limits;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod())) {
            for (var entry : settings.policies().entrySet()) {
                var budget = entry.getValue();
                if (budget.path().equals(request.getServletPath())) {
                    try {
                        limits.require(
                                entry.getKey() + ":ip",
                                request.getRemoteAddr(),
                                budget.sourceLimit(),
                                Duration.ofSeconds(budget.windowSeconds()));
                    } catch (BusinessException error) {
                        SecurityConfiguration.writeFailure(
                                mapper, request, response, error.getError(), error.getParams());
                        return;
                    }
                    request.setAttribute(CHECKED_POLICY, entry.getKey());
                    break;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
