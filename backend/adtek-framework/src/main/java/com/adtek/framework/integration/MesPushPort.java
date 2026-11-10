package com.adtek.framework.integration;

import java.util.Map;

/**
 * 推送一个批次到 MES（接口 ①），按应答归类；不抛异常。
 *
 * <p>请求：HTTPS，默认发原始 JSON（由 {@code bodyGz} 解压得到，逐字节固定），{@link Target#gzip()} 时直接发 gzip 并带 {@code
 * Content-Encoding: gzip}；不跟随 3xx。认证：TOKEN 固定 Bearer 令牌 / HMAC-SHA256
 * 签名（X-Sn-Timestamp、X-Sn-Signature）/ NONE 无认证。应答：HTTP 200 且 code = OK（非 ping 时 batchNo
 * 相符）为已收到；超时、5xx、断连、409 BATCH_IN_PROGRESS、429、503 可重试； 其余不重试。事务：不参与数据库事务，调用方不得在持有数据库锁的事务内调用。
 */
public interface MesPushPort {
    Outcome send(Target target, String batchNo, byte[] bodyGz, int attempt, boolean ping);

    String RECEIVED = "RECEIVED";
    String RETRY = "RETRY";
    String FAIL = "FAIL";

    /** 推送目标；credential 为明文（调用方解密后传入）。 */
    record Target(
            String url, String authType, String credential, int timeoutSeconds, boolean gzip) {}

    /**
     * 推送结果。
     *
     * @param kind RECEIVED 已收到 / RETRY 可重试 / FAIL 不重试
     * @param body RECEIVED 时 MES 的应答体
     * @param code 失败码（TIMEOUT、NETWORK、AUTH、ADDRESS、TOO_LARGE、BAD_REQUEST、RESPONSE_INVALID 等）
     * @param message 失败说明
     * @param httpStatus HTTP 状态（没有应答为 null）
     * @param retryAfter 应答头 Retry-After 秒数（没有为 null）
     */
    record Outcome(
            String kind,
            Map<String, Object> body,
            String code,
            String message,
            Integer httpStatus,
            Integer retryAfter) {}
}
