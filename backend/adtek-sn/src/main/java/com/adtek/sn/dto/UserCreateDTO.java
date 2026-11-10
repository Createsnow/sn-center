package com.adtek.sn.dto;

import java.util.List;

/** 新建账户：MES 接口账户不填密码（系统生成密钥）。 */
public class UserCreateDTO {
    public String empNo;
    public String name;
    public String role;
    public String factoryCode;
    public List<String> factoryCodes;
    public String password;
    public String ipWhitelist;
}
