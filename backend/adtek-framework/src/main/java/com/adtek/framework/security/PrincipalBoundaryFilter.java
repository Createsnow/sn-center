package com.adtek.framework.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** 模板只接受人员主体；新增服务身份须显式设计认证、协议面与权限测试。 */
final class PrincipalBoundaryFilter extends OncePerRequestFilter {
    private final ObjectMapper json;

    PrincipalBoundaryFilter(ObjectMapper json, RateLimitService limits) {
        this.json = json;
    }

    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ActorAuthenticationToken token
                && !"USER".equals(token.getPrincipal().principalType())) {
            SecurityConfiguration.writeFailure(
                    json,
                    request,
                    response,
                    com.adtek.framework.constant.ErrorCodeConstants.FORBIDDEN,
                    java.util.Map.of());
            return;
        }
        chain.doFilter(request, response);
    }
}
