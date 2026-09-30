package com.adtek.system.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
/** 账号管理投影，所有 ID/版本为文本，永远没有 passwordHash。 */
@Schema(requiredProperties = {"id", "username", "displayName", "deptId", "deptName", "enabled", "mustChangePassword", "version", "roleIds", "factoryIds"})
public record ManagedUserVO(String id, String username, String displayName,
        @Schema(types = {"string", "null"}) String deptId, @Schema(types = {"string", "null"}) String deptName, Boolean enabled, Boolean mustChangePassword,
        String version, List<String> roleIds, List<String> factoryIds) {}
