package com.adtek.equipment.domain;

import com.baomidou.mybatisplus.annotation.*;

/** 设备台账持久实体；所属部门和创建人仅由服务器身份装配，禁止客户端赋值。 */
@TableName("equipment_asset")
public class EquipmentEntity {
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

    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean value) {
        this.enabled = value;
    }

    private Long version;

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long value) {
        this.version = value;
    }

    private Long deptId;

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long value) {
        this.deptId = value;
    }

    private Long createBy;

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long value) {
        this.createBy = value;
    }

    private Long updateBy;

    public Long getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(Long value) {
        this.updateBy = value;
    }

    private java.time.Instant createTime;

    public java.time.Instant getCreateTime() {
        return createTime;
    }

    public void setCreateTime(java.time.Instant value) {
        this.createTime = value;
    }

    private java.time.Instant updateTime;

    public java.time.Instant getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(java.time.Instant value) {
        this.updateTime = value;
    }
}
