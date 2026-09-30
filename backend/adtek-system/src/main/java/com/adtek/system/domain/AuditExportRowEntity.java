package com.adtek.system.domain;
/** 固定序号与归属证据；csvLine为白名单字段的安全CSV文本，不含原始JSON。 */
public record AuditExportRowEntity(String taskId,int ordinal,Long actorDeptId,Long userId,String csvLine) {}
