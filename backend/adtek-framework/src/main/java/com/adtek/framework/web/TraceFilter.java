package com.adtek.framework.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 每次 HTTP 调用单独生成追踪号；不信任外部 header，也不把 traceId 当业务操作号。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
    public static final String KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(KEY, traceId);
        response.setHeader("X-Trace-Id", traceId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(KEY, traceId)) {
            chain.doFilter(request, response);
        }
    }

    public static String current(HttpServletRequest request) {
        Object value = request.getAttribute(KEY);
        return value instanceof String text ? text : "";
    }
}
