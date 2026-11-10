package com.adtek.sn.domain;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 生产组织（金蝶组织机构同步，只读）+ MES 对接配置（sn_factory）。 */
@TableName("sn_factory")
public class FactoryEntity {
    public String factoryCode;
    public String factoryName;
    public boolean enabled;
    public boolean mesEnabled;
    public String mesUrl;
    public String mesAuthType;
    public String mesCredential;
    public boolean mesGzip;
    public int batchSize;
    public int pushTimeoutSec;
    public int pushConcurrency;
    public int tzOffsetMin;
    public String cfgStatus;
    public String cfgTested;
    public String cfgMessage;
    public LocalDateTime cfgCheckedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
