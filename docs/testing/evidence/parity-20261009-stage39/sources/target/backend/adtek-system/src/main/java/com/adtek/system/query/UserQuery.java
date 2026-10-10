package com.adtek.system.query;

import jakarta.validation.constraints.*;

/** 有界偏移分页，工号/姓名模糊查询；不接受任意列名或排序 SQL。 */
public class UserQuery {
    @Min(1)
    @Max(100000)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int size = 20;

    @Size(max = 64)
    private String keyword;

    private Boolean enabled;

    @Size(max = 20)
    private java.util.List<@Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String> roleCodes =
            java.util.List.of();

    @Size(max = 20)
    private java.util.List<@Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String> excludedRoleCodes =
            java.util.List.of();

    @Pattern(regexp = "[1-9][0-9]{0,18}")
    private String factoryId;

    public java.util.List<String> getRoleCodes() {
        return roleCodes;
    }

    public void setRoleCodes(java.util.List<String> value) {
        roleCodes = value;
    }

    public java.util.List<String> getExcludedRoleCodes() {
        return excludedRoleCodes;
    }

    public void setExcludedRoleCodes(java.util.List<String> value) {
        excludedRoleCodes = value;
    }

    public String getFactoryId() {
        return factoryId;
    }

    public void setFactoryId(String value) {
        factoryId = value;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
