package com.adtek.framework.security;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/** 刷新Cookie只有HTTP边界可写；清除与签发使用相同路径和安全属性。 */
public final class RefreshCookie {
    public static final String NAME = "ADTEK-REFRESH";

    private RefreshCookie() {}

    public static void write(
            HttpServletResponse response,
            SecuritySettings settings,
            String credential,
            Duration age) {
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(NAME, credential)
                        .httpOnly(true)
                        .secure(settings.cookieSecure())
                        .sameSite("Strict")
                        .path("/api/v1/system/auth")
                        .maxAge(age)
                        .build()
                        .toString());
    }
}
