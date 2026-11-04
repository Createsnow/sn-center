package com.adtek.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 配置投影；所有 ID 和版本使用精确字符串。 */
@Schema(requiredProperties = {"id", "name", "parentId", "enabled", "version"})
public record DepartmentVO(
        String id,
        String name,
        @Schema(types = {"string", "null"}) String parentId,
        Boolean enabled,
        String version) {}
