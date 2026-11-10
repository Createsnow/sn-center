package com.adtek.sn.dto;

/** 新建规则：须填 PI（categoryValue）；factoryCode = 所属生产组织（系统管理员不填 = 全局规则）。 */
public class RuleCreateDTO extends RuleSpecDTO {
    public String ruleCode;
    public String ruleName;
    public String categoryScope;
    public String categoryValue;
    public String factoryCode;
}
