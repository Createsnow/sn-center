package com.adtek.framework.security;

import com.adtek.common.constant.CommonErrorCode;
import com.adtek.framework.web.ResultResponseWriter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 安全过滤链：默认拒绝，只放行健康探针（local profile 另放行 OpenAPI 文档）与配置的匿名入口；{@code /api/**} 要求认证。
 *
 * <p>认证为 JWT（HttpOnly Cookie 或 Bearer）+ MySQL 服务端会话 + MySQL 当前授权（{@link
 * SessionAuthenticationFilter}）；授权仍在 服务用例中检查。写请求使用 SPA 模式的 CSRF（XSRF-TOKEN Cookie + X-XSRF-TOKEN
 * 头）；用 Bearer 头认证的对接方请求与机器接口路径不带 Cookie 凭据，不受 CSRF 影响，因此不校验 CSRF。机器接口（如 MES，{@link
 * SecurityProperties#machinePaths()}）在链上放行， 由提供方用例按接口规范认证（工号 + 密钥、来源地址白名单、失败锁定）并返回该接口自己的错误结构。不使用
 * Servlet 会话，失败统一写出 {@code Result} 信封。
 *
 * <p>只在 Servlet Web 应用中生效：migrate profile 是非 Web 进程，没有 HttpSecurity。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            Environment environment,
            ResultResponseWriter writer,
            SecurityProperties properties,
            SessionService sessions,
            PrincipalPort principals) {
        checkSecret(properties);
        boolean apiDocsPublic = environment.acceptsProfiles(Profiles.of("local"));
        RequestMatcher machine = matcher(properties.machinePaths());
        RequestMatcher open = matcher(properties.publicPaths());
        http.authorizeHttpRequests(
                        auth -> {
                            // 错误转发沿用原请求已作出的授权结论，避免把 500 掩盖成 401。
                            auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                            auth.requestMatchers(
                                            HttpMethod.GET,
                                            "/actuator/health",
                                            "/actuator/health/liveness",
                                            "/actuator/health/readiness")
                                    .permitAll();
                            if (apiDocsPublic) {
                                auth.requestMatchers(
                                                HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**")
                                        .permitAll();
                            }
                            auth.requestMatchers(machine).permitAll();
                            auth.requestMatchers(open).permitAll();
                            auth.requestMatchers("/api/**").authenticated();
                            auth.anyRequest().denyAll();
                        })
                .csrf(
                        csrf ->
                                csrf.spa()
                                        .requireCsrfProtectionMatcher(
                                                request ->
                                                        !SAFE_METHODS.contains(request.getMethod())
                                                                && !machine.matches(request)
                                                                && !hasBearer(request)))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterBefore(
                        new SessionAuthenticationFilter(
                                sessions, principals, writer, machine, properties.cookieSecure()),
                        AuthorizationFilter.class)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        writer.write(
                                                                response,
                                                                CommonErrorCode.UNAUTHENTICATED))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        writer.write(
                                                                response,
                                                                CommonErrorCode.FORBIDDEN)));
        return http.build();
    }

    /** 生产环境（ENVIRONMENT=production）密钥为空、为示例值或过短时拒绝启动：否则任何人都能伪造登录令牌。 */
    static void checkSecret(SecurityProperties properties) {
        String problem = properties.secretProblem();
        if (problem == null) {
            return;
        }
        if (properties.production()) {
            throw new IllegalStateException(problem + "：生产环境必须设置至少 32 位的随机 SN_SECRET");
        }
        log.warn("Weak session secret: {} (ENVIRONMENT=production 时将拒绝启动)", problem);
    }

    private static boolean hasBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private static RequestMatcher matcher(List<String> patterns) {
        if (patterns.isEmpty()) {
            return request -> false;
        }
        return new OrRequestMatcher(
                patterns.stream()
                        .map(
                                p ->
                                        (RequestMatcher)
                                                PathPatternRequestMatcher.withDefaults().matcher(p))
                        .toList());
    }

    /** 每个请求加载一次 CSRF 令牌，使 XSRF-TOKEN Cookie 在首个 GET 响应中即下发给单页应用。 */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
