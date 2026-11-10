package com.adtek.sn.domain;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** SN 规则版本（sn_rule_version）：编码参数，每个版本归属一张 PI。 */
@TableName("sn_rule_version")
public class RuleVersionEntity {
    public Long id;
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
}
