package com.adtek.sn.vo;
import io.swagger.v3.oas.annotations.media.Schema;
/** action沿用旧逻辑：RENAMED/UPDATED/NEW_VERSION/UNCHANGED；返回最新规则。 */
@Schema(requiredProperties={"action","rule"})
public record RuleChangeVO(String action,RuleVO rule) {}
