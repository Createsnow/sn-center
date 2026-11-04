package com.adtek.framework.audit;

import com.adtek.framework.security.ActorAuthenticationToken;
import com.adtek.framework.web.TraceFilter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.slf4j.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** 位于SecurityContext清理内层、限流/CSRF外层；读取最终状态而不缓存/解析请求体或响应体。 */
public final class RequestFailureAuditFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestFailureAuditFilter.class);
    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private final RequestFailureAuditPort audit;
    private final RequestAuditRoutes routes;

    public RequestFailureAuditFilter(RequestFailureAuditPort audit, RequestAuditRoutes routes) {
        this.audit = audit;
        this.routes = routes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            int status = completed ? response.getStatus() : 500;
            if (request.getServletPath().startsWith("/api/")
                    && status >= 400
                    && status <= 599
                    && !Boolean.TRUE.equals(request.getAttribute(RequestAuditState.RECORDED))) {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                Long actor =
                        authentication instanceof ActorAuthenticationToken token
                                        && token.isAuthenticated()
                                ? token.getPrincipal().userId()
                                : null;
                String method =
                        METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
                Object code = request.getAttribute(RequestAuditState.ERROR_CODE);
                try {
                    audit.record(
                            new RequestFailureAuditPort.Event(
                                    actor,
                                    method,
                                    routes.template(method, request.getServletPath()),
                                    status,
                                    code instanceof Integer number ? number : null,
                                    TraceFilter.current(request)));
                    request.setAttribute(RequestAuditState.RECORDED, Boolean.TRUE);
                } catch (RuntimeException error) {
                    // 不输出底层SQL、请求头、异常message或body；DB故障时保留拒绝并给出可追踪的告警。
                    LOG.warn(
                            "Request failure audit unavailable traceId={} exceptionType={}",
                            TraceFilter.current(request),
                            error.getClass().getSimpleName());
                }
            }
        }
    }
}
