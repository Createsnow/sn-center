package com.adtek.system.dto;

import jakarta.validation.constraints.*;

/**
 * 固定筛选的CSV导出意图；from/to必须显式给出，重试保持同号和同一时间窗。 可选文本筛选未提供/null/空串均归一为不筛选，非空值按原字符精确筛选；多个条件取交集。
 *
 * @param operationId 当前主体下稳定UUID，不接受traceId代替
 * @param from 含时区的起点，包含
 * @param to 含时区的终点，不包含，窗口至多31天
 * @param actorUsername 日志内账号历史快照，不随当前账号改名变化
 * @param userId 精确十进制主体ID字符串，不经浮点数转换
 * @param traceId 日志追踪号筛选，会参与载荷摘要；不同于当前HTTP请求追踪头
 * @param success null表示不筛选，false仍是明确失败筛选
 */
public record AuditExportCommandDTO(
        @NotBlank String operationId,
        @NotBlank String from,
        @NotBlank String to,
        String action,
        String module,
        String actorUsername,
        String userId,
        String traceId,
        Boolean success) {}
