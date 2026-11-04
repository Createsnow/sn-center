package com.adtek.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 审计列表投影，不含原始changes或任何请求内容，ID保持字符串。 */
@Schema(
        requiredProperties = {
            "id",
            "userId",
            "actorDeptId",
            "actorUsername",
            "moduleName",
            "businessType",
            "action",
            "objectId",
            "success",
            "traceId",
            "createTime",
            "requestMethod",
            "requestRoute",
            "httpStatus",
            "errorCode"
        })
public record AuditLogVO(
        String id,
        @Schema(types = {"string", "null"}) String userId,
        @Schema(types = {"string", "null"}) String actorDeptId,
        @Schema(types = {"string", "null"}) String actorUsername,
        String moduleName,
        String businessType,
        String action,
        @Schema(types = {"string", "null"}) String objectId,
        Boolean success,
        @Schema(types = {"string", "null"}) String traceId,
        java.time.Instant createTime,
        @Schema(types = {"string", "null"}) String requestMethod,
        @Schema(types = {"string", "null"}) String requestRoute,
        @Schema(types = {"integer", "null"}) Integer httpStatus,
        @Schema(types = {"integer", "null"}) Integer errorCode) {}
