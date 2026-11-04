package com.adtek.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** accessToken 只供客户端内存；刷新凭据不出现在此响应中。 */
@Schema(requiredProperties = {"accessToken", "tokenType", "expiresAt", "profile"})
public record AccessTokenVO(
        String accessToken,
        @Schema(allowableValues = "Bearer") String tokenType,
        Instant expiresAt,
        UserProfileVO profile) {
    @Override
    public String toString() {
        return "AccessTokenVO[redacted]";
    }
}
