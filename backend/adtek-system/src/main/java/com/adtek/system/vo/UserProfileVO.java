package com.adtek.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

@Schema(
        requiredProperties = {
            "id",
            "username",
            "displayName",
            "deptId",
            "language",
            "mustChangePassword",
            "permissions",
            "authorizationVersion"
        })
public record UserProfileVO(
        String id,
        String username,
        String displayName,
        @Schema(types = {"string", "null"}) String deptId,
        @Schema(allowableValues = {"zh-CN", "en", "vi"}) String language,
        Boolean mustChangePassword,
        Set<String> permissions,
        String authorizationVersion) {}
