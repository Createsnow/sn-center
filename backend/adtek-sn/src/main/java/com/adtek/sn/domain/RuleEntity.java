package com.adtek.sn.domain;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** SN 规则（sn_rule）。 */
@TableName("sn_rule")
public class RuleEntity {
    public Long id;
    public String ruleCode;
    public String ruleName;
    public String categoryScope;
    public String categoryValue;
    public String factoryCode;
    public int currentVersion;
    public String createdBy;
    public LocalDateTime createdAt;
    public String updatedBy;
    public LocalDateTime updatedAt;
}
