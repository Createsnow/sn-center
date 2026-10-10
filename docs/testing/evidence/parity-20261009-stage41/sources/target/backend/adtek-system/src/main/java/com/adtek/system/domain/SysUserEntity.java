package com.adtek.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** 持久账号。passwordHash 不进入 VO；停用保留历史身份，不提供通用删除。 */
@TableName("sys_user")
public class SysUserEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String principalType;

    public String getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(String value) {
        principalType = value;
    }

    private String username;
    private String passwordHash;
    private String displayName;
    private Long deptId;
    private Boolean enabled;
    private Boolean mustChangePassword;
    private Long securityVersion;
    private Long credentialVersion;

    public Long getCredentialVersion() {
        return credentialVersion;
    }

    public void setCredentialVersion(Long value) {
        credentialVersion = value;
    }

    private String language;
    private Long createBy;
    private Instant createTime;
    private Long updateBy;
    private Instant updateTime;
    private Instant lastLoginAt;

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant value) {
        lastLoginAt = value;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Long getSecurityVersion() {
        return securityVersion;
    }

    public void setSecurityVersion(Long securityVersion) {
        this.securityVersion = securityVersion;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }

    public Instant getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Instant createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(Long updateBy) {
        this.updateBy = updateBy;
    }

    public Instant getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Instant updateTime) {
        this.updateTime = updateTime;
    }
}
