package com.adtek.sn.vo;

import java.time.LocalDateTime;

/** 规则版本。 */
public class RuleVersionVO {
    public long id;
    public long ruleId;
    public int version;
    public String prefix;
    public String suffix;
    public int base;
    public int seqLen;
    public String charset;
    public boolean withLength;
    public int lengthPad;
    public String lengthSepBefore;
    public String lengthSepAfter;
    public boolean withUnit;
    public boolean used;
    public String claimedPi;
    public Long derivedFrom;
    public String createdBy;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public String format;
}
