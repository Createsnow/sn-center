package com.adtek.equipment.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 业务 ID 和乐观锁版本使用精确字符串，时间以 UTC ISO-8601 返回。 */
@Schema(
        requiredProperties = {
            "id",
            "code",
            "name",
            "enabled",
            "version",
            "deptId",
            "createBy",
            "createTime",
            "updateTime"
        })
public record EquipmentVO(
        String id,
        String code,
        String name,
        Boolean enabled,
        String version,
        String deptId,
        String createBy,
        Instant createTime,
        Instant updateTime) {}
