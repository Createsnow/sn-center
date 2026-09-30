package com.adtek.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 配置投影；所有 ID 和版本使用精确字符串。 */
@Schema(
        requiredProperties = {
            "id",
            "name",
            "parentId",
            "menuType",
            "permission",
            "path",
            "component",
            "sortOrder",
            "enabled",
            "version"
        })
public record ManagedMenuVO(
        String id,
        String name,
        @Schema(types = {"string", "null"}) String parentId,
        String menuType,
        @Schema(types = {"string", "null"}) String permission,
        @Schema(types = {"string", "null"}) String path,
        @Schema(types = {"string", "null"}) String component,
        Integer sortOrder,
        Boolean enabled,
        String version) {}
