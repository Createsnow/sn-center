package com.adtek.sn.vo;

import java.time.LocalDateTime;

/** 生产组织；系统管理员另看 MES 对接配置（凭证不回显）与绑定的启用账户数（full = false 时这些字段为空）。 */
public class FactoryVO {
    public String factoryCode;
    public String factoryName;
    public boolean enabled;
    public boolean mesEnabled;
    public int tzOffsetMin;
    public String mesUrl;
    public String mesAuthType;
    public Boolean mesGzip;
    public Integer batchSize;
    public Integer pushTimeoutSec;
    public Integer pushConcurrency;
    public String cfgStatus;
    public String cfgMessage;
    public LocalDateTime cfgCheckedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public Boolean credentialSet;
    public Boolean tested;
    public Long boundUsers;
}
