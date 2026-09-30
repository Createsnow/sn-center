package com.adtek.framework.security.dto;

import java.time.Instant;

/** 离线身份导入契约；旧账号状态留在原始档案，导入时一律停用，完成全量核对后才允许另行启用。 */
public record LegacyIdentityDTO(
        long id,
        String username,
        String displayName,
        String legacyPasswordHash,
        String language,
        boolean mustChangePassword,
        String legacyRole,
        boolean hasFactory,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt) {
    @Override
    public String toString() {
        return "LegacyIdentityDTO[redacted]";
    }
}
