package com.adtek.sn.dto;

/** 金蝶连接参数；未提交的项沿用当前的，应用密钥留空 = 沿用当前的（不回显）。 */
public class K3ConfigDTO {
    public String serverUrl;
    public String acctId;
    public String appId;
    public String appSecret;
    public String username;
    public Integer lcid;
    public Integer loginTimeout;
    public Integer queryTimeout;
    public Integer viewTimeout;
    public Integer pageRetries;
    public Integer pageRetrySeconds;
}
