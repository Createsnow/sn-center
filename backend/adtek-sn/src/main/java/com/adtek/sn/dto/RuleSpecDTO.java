package com.adtek.sn.dto;

/** 规则编码参数；带米数默认 true，米数补 0 位数与前后分隔符只在带米数时起作用。 */
public class RuleSpecDTO {
    public String prefix;
    public String suffix;
    public Integer base;
    public Integer seqLen;
    public String charset;
    public Boolean withLength = true;
    public Integer lengthPad = 0;
    public String sepBefore = "";
    public String sepAfter = "";
    public Boolean withUnit = false;
}
