package com.adtek.system.domain;
/** sys_dict_item 配置持久对象；ID/版本由服务及数据库生成，停用后保留历史身份。 */
public class DictionaryItemEntity {
    private Long id;
    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    /** 所属类型 ID，创建后不可转移，防止改变已使用值的业务含义。 */
    private Long typeId;
    public Long getTypeId() { return typeId; }
    public void setTypeId(Long value) { this.typeId = value; }
    /** 类型内唯一的不可变业务值；显示文本分别存放三个语言字段。 */
    private String value;
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    private String labelZh;
    public String getLabelZh() { return labelZh; }
    public void setLabelZh(String value) { this.labelZh = value; }
    private String labelEn;
    public String getLabelEn() { return labelEn; }
    public void setLabelEn(String value) { this.labelEn = value; }
    private String labelVi;
    public String getLabelVi() { return labelVi; }
    public void setLabelVi(String value) { this.labelVi = value; }
    private Integer sortOrder;
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer value) { this.sortOrder = value; }
    /** 停用保留关联和历史 ID，不进行物理删除。 */
    private Boolean enabled;
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { this.enabled = value; }
    /** 配置乐观锁版本；与账号 security_version 分开，成功修改原子递增。 */
    private Long version;
    public Long getVersion() { return version; }
    public void setVersion(Long value) { this.version = value; }
}
