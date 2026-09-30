package com.adtek.sn.domain;

/** 单次完整快照的可恢复文件行及原授权范围；不从已转厂明细重建。 */
public record SnExportRowEntity(
        String taskId, long ordinal, Long factoryId, String pi, String cells) {}
