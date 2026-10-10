package com.adtek.sn.domain;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;

/** 编码版本；used后参数不可原位修改，摘要只包含编码参数，时间为UTC。 */
@TableName("sn_rule_version")
public class RuleVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        this.id = value;
    }

    private Long ruleId;

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long value) {
        this.ruleId = value;
    }

    private Long version;

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long value) {
        this.version = value;
    }

    private String prefix;

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String value) {
        this.prefix = value;
    }

    private String suffix;

    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String value) {
        this.suffix = value;
    }

    private Integer base;

    public Integer getBase() {
        return base;
    }

    public void setBase(Integer value) {
        this.base = value;
    }

    private Integer seqLength;

    public Integer getSeqLength() {
        return seqLength;
    }

    public void setSeqLength(Integer value) {
        this.seqLength = value;
    }

    private String alphabet;

    public String getAlphabet() {
        return alphabet;
    }

    public void setAlphabet(String value) {
        this.alphabet = value;
    }

    private Boolean used;

    public Boolean getUsed() {
        return used;
    }

    public void setUsed(Boolean value) {
        this.used = value;
    }

    private String contentDigest;

    public String getContentDigest() {
        return contentDigest;
    }

    public void setContentDigest(String value) {
        this.contentDigest = value;
    }

    private Long createdBy;

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long value) {
        this.createdBy = value;
    }

    private String createdByName;

    public String getCreatedByName() {
        return createdByName;
    }

    public void setCreatedByName(String value) {
        this.createdByName = value;
    }

    private Instant createdAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        this.createdAt = value;
    }

    private Instant updatedAt;

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant value) {
        this.updatedAt = value;
    }
}
