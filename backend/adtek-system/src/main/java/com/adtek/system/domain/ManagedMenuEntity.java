package com.adtek.system.domain;

/** sys_menu 配置持久对象；ID/版本由服务及数据库生成，停用后保留历史身份。 */
public class ManagedMenuEntity {
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long value) {
        this.id = value;
    }

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    /** 直接父节点；null 为根，祖先通过 parent_id 递归推导，不保存冗余祖先字符串。 */
    private Long parentId;

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long value) {
        this.parentId = value;
    }

    /** M=目录，C=页面，F=操作；创建后与权限标识一起保持不可变。 */
    private String menuType;

    public String getMenuType() {
        return menuType;
    }

    public void setMenuType(String value) {
        this.menuType = value;
    }

    private String permission;

    public String getPermission() {
        return permission;
    }

    public void setPermission(String value) {
        this.permission = value;
    }

    private String path;

    public String getPath() {
        return path;
    }

    public void setPath(String value) {
        this.path = value;
    }

    private String component;

    public String getComponent() {
        return component;
    }

    public void setComponent(String value) {
        this.component = value;
    }

    private Integer sortOrder;

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer value) {
        this.sortOrder = value;
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
