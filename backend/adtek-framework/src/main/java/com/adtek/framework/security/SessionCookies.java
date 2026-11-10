package com.adtek.framework.security;

import org.springframework.http.ResponseCookie;

import java.time.Duration;

/** 会话 Cookie：HttpOnly、SameSite=Strict、Path=/；部署环境带 Secure（只有本机 local profile 可关闭）。 */
public final class SessionCookies {
    private SessionCookies() {}

    public static String issue(String token, long ttlSeconds, boolean secure) {
        return ResponseCookie.from(SessionAuthenticationFilter.COOKIE, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofSeconds(ttlSeconds))
                .build()
                .toString();
    }

    public static String clear(boolean secure) {
        return ResponseCookie.from(SessionAuthenticationFilter.COOKIE, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }
}
