package com.adtek.sn.dto;

/** 修改规则：分类与 PI 都不传则不变。 */
public class RuleUpdateDTO extends RuleSpecDTO {
    public String ruleName;
    public String categoryScope;
    public String categoryValue;
}
