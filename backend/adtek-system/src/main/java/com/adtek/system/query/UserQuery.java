package com.adtek.system.query;
import jakarta.validation.constraints.*;
/** 有界偏移分页，工号/姓名模糊查询；不接受任意列名或排序 SQL。 */
public class UserQuery {
    @Min(1) @Max(100000) private int page = 1;
    @Min(1) @Max(100) private int size = 20;
    @Size(max = 64) private String keyword;
    private Boolean enabled;
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
