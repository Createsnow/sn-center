package com.adtek.sn.support;

import com.adtek.framework.integration.K3GatewayPort;

/** 金蝶连接参数（应用密钥为明文，只在内存中使用）。 */
public record K3Params(
        String serverUrl,
        String acctId,
        String appId,
        String appSecret,
        String username,
        int lcid,
        int loginTimeout,
        int queryTimeout,
        int viewTimeout,
        int pageRetries,
        int pageRetrySeconds) {

    public K3Params withSecret(String secret) {
        return new K3Params(
                serverUrl,
                acctId,
                appId,
                secret,
                username,
                lcid,
                loginTimeout,
                queryTimeout,
                viewTimeout,
                pageRetries,
                pageRetrySeconds);
    }

    public K3GatewayPort.Connection connection() {
        return new K3GatewayPort.Connection(
                serverUrl,
                acctId,
                appId,
                appSecret,
                username,
                lcid,
                loginTimeout,
                queryTimeout,
                viewTimeout,
                Math.max(0, pageRetries),
                Math.max(0, pageRetrySeconds));
    }
}
