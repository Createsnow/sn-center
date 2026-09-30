package com.adtek.sn.domain;

/** 持久事实或显式SQL投影；不直接作为HTTP响应。 */
public record AcquireResultEntity(String taskId, long ordinal, String sn, String outcome) {}
