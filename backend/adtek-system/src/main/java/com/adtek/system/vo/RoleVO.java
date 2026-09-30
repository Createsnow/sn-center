package com.adtek.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 配置投影；所有 ID 和版本使用精确字符串。 */
@Schema(
        requiredProperties = {
            "id",
            "code",
            "name",
            "dataScope",
            "factoryScope",
            "enabled",
            "version",
            "menuIds",
            "deptIds"
        })
public record RoleVO(
        String id,
        String code,
        String name,
        Integer dataScope,
        Integer factoryScope,
        Boolean enabled,
        String version,
        List<String> menuIds,
        List<String> deptIds) {}
