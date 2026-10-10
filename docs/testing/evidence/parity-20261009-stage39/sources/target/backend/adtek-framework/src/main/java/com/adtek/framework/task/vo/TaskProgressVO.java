package com.adtek.framework.task.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 业务看板的执行进度投影；processed 不代表事务已经提交，不包含任务输入或文件能力。 */
@Schema(requiredProperties = {"state", "processed"})
public record TaskProgressVO(String state, String processed) {}
