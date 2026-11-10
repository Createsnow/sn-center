package com.adtek.sn.vo;

/** 物料行（或 PI 默认）生效规则的说明：来源、选法、规则、本 PI 用的版本、格式、第一个号。 */
public class RuleInfoVO {
    public String source;
    public String mode;
    public String plan;
    public Long ruleId;
    public String ruleCode;
    public String ruleName;
    public Long versionId;
    public Integer version;
    public String otherPi;
    public CustomRuleVO custom;
    public Long seqStart;
    public String format;
    public String firstSn;
    public Long maxSeq;
    public String prefix;
    public Integer base;
    public Integer seqLen;
    public Boolean withLength;
    public Integer lengthPad;
    public String sepBefore;
    public String sepAfter;
    public Boolean withUnit;
    public String suffix;
    public String templatePrefix;
}
