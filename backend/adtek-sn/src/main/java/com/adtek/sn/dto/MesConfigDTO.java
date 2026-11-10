package com.adtek.sn.dto;

/** MES 对接配置；凭证留空 = 沿用已存的（不回显）；为 null 的项沿用已存的。 */
public class MesConfigDTO {
    public String mesUrl;
    public String mesAuthType;
    public String mesCredential;
    public Boolean mesGzip;
    public Integer batchSize;
    public Integer pushTimeoutSec;
    public Integer pushConcurrency;
    public Integer tzOffsetMin;
}
