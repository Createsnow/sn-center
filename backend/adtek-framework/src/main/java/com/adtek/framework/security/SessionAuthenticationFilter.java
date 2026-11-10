package com.adtek.framework.security;

import com.adtek.common.constant.CommonErrorCode;
import com.adtek.common.core.ErrorCode;
import com.adtek.framework.web.ResultResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 会话认证：令牌取自 HttpOnly Cookie {@value #COOKIE}（页面）或 {@code Authorization: Bearer <JWT>}（对接方），依次校验
 * JWT、MySQL 会话（未吊销、未过期、口令版本一致）与 {@link PrincipalPort} 当前授权，任何一项失效即以 Result 信封拒绝。
 *
 * <p>没有令牌的请求按匿名继续，由授权规则决定 401；机器接口路径（{@link SecurityProperties#machinePaths()}）不在这里认证。
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    public static final String COOKIE = "SN_SESSION";

    private final SessionService sessions;
    private final PrincipalPort principals;
    private final ResultResponseWriter writer;
    private final RequestMatcher machinePaths;
    private final boolean cookieSecure;

    public SessionAuthenticationFilter(
            SessionService sessions,
            PrincipalPort principals,
            ResultResponseWriter writer,
            RequestMatcher machinePaths,
            boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
        this.sessions = sessions;
        this.principals = principals;
        this.writer = writer;
        this.machinePaths = machinePaths;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return machinePaths.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = bearer(request);
        boolean fromCookie = false;
        if (token == null) {
            token = cookie(request);
            fromCookie = token != null;
        }
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<SessionService.Session> verified = sessions.verify(token);
        if (verified.isEmpty()) {
            reject(request, response, AuthErrorCode.TOKEN_INVALID, fromCookie);
            return;
        }
        SessionService.Session session = verified.get();
        if (session.revokeReason() != null) {
            ErrorCode code =
                    switch (session.revokeReason()) {
                        case SessionService.REASON_REPLACED -> AuthErrorCode.SESSION_REPLACED;
                        case SessionService.REASON_PASSWORD -> AuthErrorCode.PASSWORD_CHANGED;
                        default -> AuthErrorCode.TOKEN_INVALID;
                    };
            reject(request, response, code, fromCookie);
            return;
        }
        Optional<PrincipalPort.Snapshot> loaded = principals.load(session.userId());
        if (loaded.isEmpty()) {
            reject(request, response, AuthErrorCode.TOKEN_INVALID, fromCookie);
            return;
        }
        PrincipalPort.Snapshot user = loaded.get();
        if (user.passwordVersion() != session.passwordVersion()) {
            reject(request, response, AuthErrorCode.PASSWORD_CHANGED, fromCookie);
            return;
        }
        if (!user.active()) {
            reject(request, response, AuthErrorCode.ACCOUNT_DISABLED, fromCookie);
            return;
        }
        if (user.interfaceOnly()) {
            writer.write(response, CommonErrorCode.FORBIDDEN);
            return;
        }
        if (user.scopeMissing()) {
            writer.write(response, AuthErrorCode.USER_NO_FACTORY);
            return;
        }
        AuthPrincipal principal =
                new AuthPrincipal(
                        user.userId(),
                        user.empNo(),
                        user.name(),
                        user.role(),
                        user.factoryCode(),
                        user.factories(),
                        session.kind() == SessionService.Kind.PAGE
                                ? AuthPrincipal.PAGE
                                : AuthPrincipal.API,
                        session.id());
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, AuthorityUtils.NO_AUTHORITIES);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode code,
            boolean clearCookie)
            throws IOException {
        if (clearCookie) {
            response.addHeader("Set-Cookie", SessionCookies.clear(cookieSecure));
        }
        writer.write(response, code);
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null
                || header.length() < 8
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String value = header.substring(7).strip();
        // 「工号:密钥」形式只用于 MES 机器接口，不是会话令牌
        return value.isEmpty() || value.indexOf(':') >= 0 ? null : value;
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }
}
