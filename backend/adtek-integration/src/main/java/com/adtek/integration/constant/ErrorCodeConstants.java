package com.adtek.integration.constant;

import com.adtek.common.core.ErrorCode;

/** integration独占号段；外部拒绝、传输故障与响应结构问题分开，错误不含外部原报文或秘密。 */
public final class ErrorCodeConstants {
    public static final ErrorCode K3_CONFIG_MISSING =
            new ErrorCode(1_005_000_001, 503, "integration.k3ConfigMissing");
    public static final ErrorCode K3_LOGIN_REJECTED =
            new ErrorCode(1_005_000_002, 502, "integration.k3LoginRejected");
    public static final ErrorCode K3_UNAVAILABLE =
            new ErrorCode(1_005_000_003, 503, "integration.k3Unavailable");
    public static final ErrorCode K3_RESPONSE_INVALID =
            new ErrorCode(1_005_000_004, 502, "integration.k3ResponseInvalid");
    public static final ErrorCode K3_RESOURCE_LIMIT =
            new ErrorCode(1_005_000_005, 409, "integration.k3ResourceLimit");
    public static final ErrorCode K3_QUERY_REJECTED =
            new ErrorCode(1_005_000_006, 502, "integration.k3QueryRejected");

    private ErrorCodeConstants() {}
}
