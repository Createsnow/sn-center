package com.adtek.framework.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端地址（留痕、MES 来源地址白名单）：经 Web 反向代理时取 nginx 设置的 {@code X-Real-IP}，直连取对端地址。
 *
 * <p>API 只在容器内网开放、由 Web 转发，因此该头由可信的 nginx 写入（会覆盖客户端提交的同名头）。
 */
public final class ClientAddress {
    private ClientAddress() {}

    public static String of(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            String value = real.strip();
            return value.length() > 64 ? value.substring(0, 64) : value;
        }
        return request.getRemoteAddr();
    }
}
