package com.adtek.framework.security;

import com.adtek.common.core.ErrorCode;
import com.adtek.common.core.Result;
import com.adtek.framework.audit.*;
import com.adtek.framework.constant.ErrorCodeConstants;
import com.adtek.framework.web.TraceFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/** 标准 JWT 解码后仍核验 Redis 会话及当前 MySQL 授权。 Cookie 入口保留 CSRF，公共路径逐条登记。 */
@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    /** Firewall在过滤链外拒绝请求；此处仍保持匿名主体和固定路由，不解析非法URL或凭据。 */
    @Bean
    org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer
            firewallFailures(ObjectMapper mapper, RequestFailureAuditPort audit) {
        return web ->
                web.requestRejectedHandler(
                        (request, response, error) -> {
                            writeFailure(
                                    mapper,
                                    request,
                                    response,
                                    ErrorCodeConstants.FORBIDDEN,
                                    Map.of());
                            if (request.getRequestURI().startsWith("/api/")) {
                                String method =
                                        java.util.Set.of(
                                                                "GET", "POST", "PUT", "PATCH",
                                                                "DELETE", "HEAD", "OPTIONS")
                                                        .contains(request.getMethod())
                                                ? request.getMethod()
                                                : "OTHER";
                                try {
                                    audit.record(
                                            new RequestFailureAuditPort.Event(
                                                    null,
                                                    method,
                                                    "/api/**",
                                                    403,
                                                    ErrorCodeConstants.FORBIDDEN.code(),
                                                    TraceFilter.current(request)));
                                } catch (RuntimeException unavailable) {
                                    org.slf4j.LoggerFactory.getLogger(SecurityConfiguration.class)
                                            .warn(
                                                    "Firewall rejection audit unavailable"
                                                            + " traceId={} exceptionType={}",
                                                    TraceFilter.current(request),
                                                    unavailable.getClass().getSimpleName());
                                }
                            }
                        });
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper mapper,
            SecuritySettings settings,
            SessionJwtAuthenticationConverter converter,
            RateLimitService limits,
            RateLimitSettings budgets,
            RequestFailureAuditPort failureAudit,
            java.util.List<RequestAuditRoutes> auditRoutes)
            throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName("ADTEK-CSRF");
        csrf.setCookieCustomizer(
                cookie ->
                        cookie.path("/")
                                .httpOnly(true)
                                .secure(settings.cookieSecure())
                                .sameSite("Strict"));
        AuthenticationEntryPoint unauthenticated =
                (request, response, error) ->
                        writeFailure(
                                mapper,
                                request,
                                response,
                                error instanceof ActorAuthenticationException actorError
                                        ? actorError.error()
                                        : ErrorCodeConstants.SESSION_INVALID,
                                Map.of());
        AccessDeniedHandler forbidden =
                (request, response, error) ->
                        writeFailure(
                                mapper, request, response, ErrorCodeConstants.FORBIDDEN, Map.of());
        return http.sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .csrf(
                        config ->
                                config.csrfTokenRepository(csrf)
                                        .ignoringRequestMatchers("/api/v1/integrations/**"))
                .addFilterAfter(
                        new PrincipalBoundaryFilter(mapper, limits),
                        org.springframework.security.oauth2.server.resource.web.authentication
                                .BearerTokenAuthenticationFilter.class)
                .addFilterBefore(
                        new EntryRateLimitFilter(limits, budgets, mapper), CsrfFilter.class)
                .addFilterBefore(
                        new RequestFailureAuditFilter(
                                failureAudit,
                                (method, path) ->
                                        auditRoutes.stream()
                                                .map(routes -> routes.template(method, path))
                                                .filter(template -> !template.equals("/api/**"))
                                                .findFirst()
                                                .orElse("/api/**")),
                        EntryRateLimitFilter.class)
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                HttpMethod.GET,
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/api/v1/system/auth/csrf")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/system/auth/login",
                                                "/api/v1/system/auth/refresh",
                                                "/api/v1/integrations/auth/token")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        resource ->
                                resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(unauthenticated)
                                        .accessDeniedHandler(forbidden))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(unauthenticated)
                                        .accessDeniedHandler(forbidden))
                .build();
    }

    static void writeFailure(
            ObjectMapper mapper,
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode error,
            Map<String, String> params)
            throws IOException {
        RequestAuditState.error(request, error.code());
        response.setStatus(error.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (error.httpStatus() == 429) {
            response.setHeader("Retry-After", params.getOrDefault("retryAfter", "1"));
        }
        mapper.writeValue(
                response.getOutputStream(),
                Result.failure(error, params, TraceFilter.current(request)));
    }
}
