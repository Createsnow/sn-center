package com.adtek.framework.task.domain;

/** 独立进度仅表示本代已处理数量，不代表业务已提交。 */
public record ProgressEntity(String taskId, long executionEpoch, long processed, String phase) {}
