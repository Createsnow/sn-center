package com.adtek.sn.dto;

/** 自定义规则：前缀必填；进制默认 10、流水位数默认 4、默认不带米数与单位；起始号选填。 */
public class CustomRuleDTO {
    public String prefix;
    public Integer base;
    public Integer seqLen;
    public Boolean withLength = false;
    public Integer lengthPad = 0;
    public String sepBefore = "";
    public String sepAfter = "";
    public Boolean withUnit = false;
    public String suffix;
    public Long seqStart;
}
