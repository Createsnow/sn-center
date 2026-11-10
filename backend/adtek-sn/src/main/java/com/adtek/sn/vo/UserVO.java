package com.adtek.sn.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 账户视图；secret 只在新建 MES 接口账户、重置密钥时返回。 */
public class UserVO {
    public long id;
    public String empNo;
    public String name;
    public String role;
    public String factoryCode;
    public String factoryName;

    /** 授权的生产组织：普通操作员、审计员 = 绑定的组织，MES 接口账户 = 本组织；系统管理员为空 = 全部。 */
    public List<String> factoryCodes;

    public List<String> factoryNames;
    public String status;
    public boolean superAdmin;
    public String ipWhitelist;
    public LocalDateTime lockedUntil;
    public int tzOffsetMin;
    public String lang;
    public LocalDateTime lastLoginAt;
    public LocalDateTime createdAt;
    public String createdBy;
    public LocalDateTime disabledAt;
    public String disabledBy;
    public String secret;
}
