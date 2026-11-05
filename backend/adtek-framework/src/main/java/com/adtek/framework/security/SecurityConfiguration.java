package com.adtek.framework.security;

import com.adtek.common.constant.CommonErrorCode;
import com.adtek.framework.web.ResultResponseWriter;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全过滤链骨架：默认拒绝，只放行健康探针（local profile 另放行 OpenAPI 文档）；{@code /api/**} 要求认证。
 *
 * <p>模板不内置认证机制与账号，因此未认证请求一律 401。项目实现 JWT + Redis 会话 + MySQL 当前授权时，在本链中加入认证过滤器（或资源服务器配置），
 * 并为登录、刷新入口单独放行与限流；授权仍在服务用例中检查。写请求使用 SPA 模式的 CSRF（XSRF-TOKEN Cookie + X-XSRF-TOKEN 头）。 不使用 Servlet
 * 会话，失败统一写出 {@code Result} 信封。
 *
 * <p>只在 Servlet Web 应用中生效：migrate profile 是非 Web 进程，没有 HttpSecurity。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, Environment environment, ResultResponseWriter writer) {
        boolean apiDocsPublic = environment.acceptsProfiles(Profiles.of("local"));
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
                            auth.requestMatchers("/api/**").authenticated();
                            auth.anyRequest().denyAll();
                        })
                .csrf(csrf -> csrf.spa())
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
}
