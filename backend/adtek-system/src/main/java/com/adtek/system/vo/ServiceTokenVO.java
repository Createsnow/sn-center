package com.adtek.system.vo;

import java.time.Instant;

public record ServiceTokenVO(String accessToken, String tokenType, Instant expiresAt) {
    @Override
    public String toString() {
        return "ServiceTokenVO[redacted]";
    }
}
