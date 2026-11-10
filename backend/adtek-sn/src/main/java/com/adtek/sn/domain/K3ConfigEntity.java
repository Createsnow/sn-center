package com.adtek.sn.domain;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 金蝶连接参数（sn_k3_config，只有一行 id = 1；应用密钥为密文）。 */
@TableName("sn_k3_config")
public class K3ConfigEntity {
    public Integer id;
    public String serverUrl;
    public String acctId;
    public String appId;
    public String appSecret;
    public String username;
    public int lcid;
    public int loginTimeout;
    public int queryTimeout;
    public int viewTimeout;
    public int pageRetries;
    public int pageRetrySeconds;
    public String updatedBy;
    public LocalDateTime updatedAt;
}
