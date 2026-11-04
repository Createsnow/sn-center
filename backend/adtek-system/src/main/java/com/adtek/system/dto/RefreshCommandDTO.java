package com.adtek.system.dto;

import com.adtek.common.core.RateLimitSubject;

/** 由 Controller 从 HttpOnly Cookie 装配，不接收客户端宣称的用户或权限。 */
public record RefreshCommandDTO(String credential) implements RateLimitSubject {
    @Override
    public String toString() {
        return "RefreshCommandDTO[redacted]";
    }

    @Override
    public String rateLimitSubject() {
        if (credential == null || credential.length() != 101) {
            return null;
        }
        String sid = credential.substring(0, 36);
        return sid.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") ? sid : null;
    }
}
