package com.adtek.sn.domain;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;

/** 唯一绑定的规则头；currentVersion指编码版本，revision用于并发编辑检查。 */
@TableName("sn_rule")
public class RuleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        this.id = value;
    }

    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String value) {
        this.code = value;
    }

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    private String bindScope;

    public String getBindScope() {
        return bindScope;
    }

    public void setBindScope(String value) {
        this.bindScope = value;
    }

    private String bindValue;

    public String getBindValue() {
        return bindValue;
    }

    public void setBindValue(String value) {
        this.bindValue = value;
    }

    private Long currentVersion;

    public Long getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Long value) {
        this.currentVersion = value;
    }

    private Long revision;

    public Long getRevision() {
        return revision;
    }

    public void setRevision(Long value) {
        this.revision = value;
    }

    private Long createdBy;

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long value) {
        this.createdBy = value;
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
