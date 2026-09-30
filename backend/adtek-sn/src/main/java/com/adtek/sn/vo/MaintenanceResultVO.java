package com.adtek.sn.vo;

import java.util.List;

/** 只含schema标识、操作计划与摘要；不含数据库地址、凭据或审计行内容。计数保持文本。 */
public record MaintenanceResultVO(
        String runId,
        String action,
        boolean applied,
        String state,
        String schema,
        List<String> statements,
        String affectedRows,
        String sourceDigest,
        String archiveDigest) {}
