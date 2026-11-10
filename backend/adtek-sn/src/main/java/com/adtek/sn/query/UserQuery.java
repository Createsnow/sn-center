package com.adtek.sn.query;

/** 账户列表筛选：页签 kind（page / mes）、工号开头或姓名包含 q、角色、状态、生产组织。 */
public class UserQuery {
    public String kind;
    public String q;
    public String role;
    public String status;
    public String factoryCode;
    public Integer page;
    public Integer pageSize;
}
