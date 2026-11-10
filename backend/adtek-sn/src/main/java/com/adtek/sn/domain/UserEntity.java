package com.adtek.sn.domain;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 账户（sn_user）：页面账户与 MES 接口账户；只停用不删除。 */
@TableName("sn_user")
public class UserEntity {
    public Long id;
    public String empNo;
    public String name;
    public String role;
    public String factoryCode;
    public String status;
    public String passwordHash;
    public String secretSealed;
    public boolean mustChangePwd;
    public String lang;
    public LocalDateTime lastLoginAt;
    public LocalDateTime pwdChangedAt;
    public int authFailures;
    public LocalDateTime lockedUntil;
    public String ipWhitelist;
    public String createdBy;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public String disabledBy;
    public LocalDateTime disabledAt;
}
