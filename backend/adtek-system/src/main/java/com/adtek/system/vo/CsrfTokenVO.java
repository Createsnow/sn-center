package com.adtek.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** CSRF 响应只供同源客户端内存使用；不需要客户端反向提供任何查询参数。 */
@Schema(requiredProperties = {"headerName", "token"})
public record CsrfTokenVO(String headerName, String token) {
    @Override public String toString() { return "CsrfTokenVO[redacted]"; }
}
