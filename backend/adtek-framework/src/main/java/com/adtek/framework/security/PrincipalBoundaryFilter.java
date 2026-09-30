package com.adtek.framework.security;

import com.adtek.common.core.BusinessException;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

/** 服务令牌只进入MES协议与本人任务查询；即使误授管理权限也不能进入人员接口。 */
final class PrincipalBoundaryFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final RateLimitService limits;

    PrincipalBoundaryFilter(ObjectMapper json, RateLimitService limits) {
        this.json = json;
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ActorAuthenticationToken token) {
            String path = request.getServletPath();
            boolean integration = path.startsWith("/api/v1/integrations/");
            boolean service = "SERVICE".equals(token.getPrincipal().principalType());
            boolean taskRead =
                    "GET".equals(request.getMethod())
                            && path.matches("/api/v1/tasks/[0-9a-f-]{36}");
            // SAFETY: 此层限制协议面；具体对象、工厂和重放仍由服务当前授权检查。
            if ((service && !integration && !taskRead) || (!service && integration)) {
                SecurityConfiguration.writeFailure(
                        json,
                        request,
                        response,
                        com.adtek.framework.constant.ErrorCodeConstants.FORBIDDEN,
                        java.util.Map.of());
                return;
            }
            if (service) {
                try {
                    limits.require(
                            "mes:subject",
                            Long.toString(token.getPrincipal().userId()),
                            120,
                            Duration.ofMinutes(1));
                } catch (BusinessException error) {
                    SecurityConfiguration.writeFailure(
                            json, request, response, error.getError(), error.getParams());
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
