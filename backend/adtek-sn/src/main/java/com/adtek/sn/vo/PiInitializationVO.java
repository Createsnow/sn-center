package com.adtek.sn.vo;
import io.swagger.v3.oas.annotations.media.Schema;
/** inSnapshot只报告当前ERP快照；没有规则也能导入未知流水，历史按分页接口读取。 */
@Schema(requiredProperties={"pi","customer","inSnapshot","counter","rule","ruleCode","ruleScope","ruleBinding"})
public record PiInitializationVO(String pi,String customer,boolean inSnapshot,PiCounterVO counter,@Schema(nullable=true) RuleVersionVO rule,String ruleCode,String ruleScope,String ruleBinding) {}
