package com.adtek.system.domain;

/** sys_dict_type 配置持久对象；ID/版本由服务及数据库生成，停用后保留历史身份。 */
public class DictionaryTypeEntity {
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

    /** 停用保留关联和历史 ID，不进行物理删除。 */
    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean value) {
        this.enabled = value;
    }

    /** 配置乐观锁版本；与账号 security_version 分开，成功修改原子递增。 */
    private Long version;

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long value) {
        this.version = value;
    }
}
