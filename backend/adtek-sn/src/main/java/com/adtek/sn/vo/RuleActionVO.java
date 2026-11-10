package com.adtek.sn.vo;

/** 规则操作结果：修改的动作（UPDATED / NEW_VERSION / RENAMED / RECATEGORIZED / UNCHANGED）、选取 / 绑定后的规则说明。 */
public class RuleActionVO {
    public String action;
    public RuleViewVO rule;
    public String piNo;
    public RuleInfoVO info;
    public Long id;
    public String ruleCode;
}
