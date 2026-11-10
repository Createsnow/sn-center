package com.adtek.sn.dto;

import java.util.List;

/** 修改账户。 */
public class UserUpdateDTO {
    public String name;
    public String role;
    public String factoryCode;
    public List<String> factoryCodes;
    public String ipWhitelist;
}
