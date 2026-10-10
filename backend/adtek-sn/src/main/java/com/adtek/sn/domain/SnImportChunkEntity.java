package com.adtek.sn.domain;

/** 按原始顺序保存的导入输入块，不是已提交业务成员或可单独执行的子任务。 */
public record SnImportChunkEntity(String taskId, int ordinal, int quantity, String sns) {}
