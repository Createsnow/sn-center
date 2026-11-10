package com.adtek.integration.mes.service.impl;

import com.adtek.framework.integration.MesPushPort;

import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 推送一个批次到 MES（原系统 app/services/mes_client.py）：MES 应答只表示「已收到」，处理结果由 MES 落库后调接口 ③ 回执。
 *
 * <p>不走系统代理、不跟随 3xx；HMAC 签名串 = timestamp + "\n" + hex(SHA256(原始请求体))，放在 X-Sn-Timestamp（UTC+8）与
 * X-Sn-Signature。HTTP 200 + code = OK（非 ping 时 batchNo 相符）为已收到；超时、5xx、断连、409
 * BATCH_IN_PROGRESS、429、503 可重试；409 BATCH_CONTENT_MISMATCH、401 / 403 / 404 / 413 / 400 等不重试。
 */
@Service
public class MesPushServiceImpl implements MesPushPort {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter MES_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final HttpClient http =
            HttpClient.newBuilder()
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

    static String sign(String secret, String timestamp, byte[] raw) {
        try {
            String digest =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal(
                                    (timestamp + "\n" + digest).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String mesNow() {
        return MES_TS.format(LocalDateTime.now(ZoneOffset.UTC).plusHours(8));
    }

    private static Integer retryAfter(HttpResponse<byte[]> resp) {
        return resp.headers()
                .firstValue("Retry-After")
                .map(
                        v -> {
                            try {
                                return Math.max(0, Integer.parseInt(v.strip()));
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        })
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Object json(HttpResponse<byte[]> resp) {
        byte[] body = resp.body();
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            if (resp.headers()
                    .firstValue("Content-Encoding")
                    .map(v -> v.equalsIgnoreCase("gzip"))
                    .orElse(false)) {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = in.readAllBytes();
                }
            }
            return JSON.readValue(new String(body, StandardCharsets.UTF_8), Object.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Outcome outcome(
            String kind, String code, String message, Integer status, Integer retryAfter) {
        return new Outcome(kind, null, code, message, status, retryAfter);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Outcome send(Target t, String batchNo, byte[] bodyGz, int attempt, boolean ping) {
        if (t.url() == null || t.url().isEmpty()) {
            return outcome(FAIL, "ADDRESS", "未配置 MES 接口地址", null, null);
        }
        byte[] raw;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bodyGz))) {
            raw = in.readAllBytes();
        } catch (IOException e) {
            return outcome(FAIL, "BAD_REQUEST", "批次内容无法解压", null, null);
        }
        HttpRequest.Builder req;
        try {
            req =
                    HttpRequest.newBuilder(URI.create(t.url()))
                            .timeout(Duration.ofSeconds(Math.max(1, t.timeoutSeconds())))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("Accept", "application/json")
                            .header("Accept-Encoding", "gzip")
                            .header("X-Sn-Attempt", Integer.toString(attempt))
                            .header("X-Request-Id", UUID.randomUUID().toString())
                            .POST(HttpRequest.BodyPublishers.ofByteArray(t.gzip() ? bodyGz : raw));
        } catch (IllegalArgumentException e) {
            return outcome(RETRY, "NETWORK", ("连接中断：" + e.getMessage()), null, null);
        }
        if (t.gzip()) {
            req.header("Content-Encoding", "gzip");
        }
        if ("HMAC".equals(t.authType())) {
            String ts = mesNow();
            req.header("X-Sn-Timestamp", ts);
            req.header("X-Sn-Signature", sign(t.credential(), ts, raw));
        } else if ("TOKEN".equals(t.authType())) {
            req.header("Authorization", "Bearer " + t.credential());
        }
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            return outcome(
                    RETRY, "TIMEOUT", "等待 MES 应答超时（" + t.timeoutSeconds() + " 秒）", null, null);
        } catch (IOException e) {
            String msg = "连接中断：" + e.getClass().getSimpleName() + " " + e.getMessage();
            return outcome(
                    RETRY, "NETWORK", msg.length() > 500 ? msg.substring(0, 500) : msg, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return outcome(RETRY, "NETWORK", "连接中断：Interrupted", null, null);
        }
        Object parsed = json(resp);
        Map<String, Object> body = parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        Object code = body == null ? null : body.get("code");
        String msg = body != null && body.get("message") instanceof String s ? s : "";
        int st = resp.statusCode();
        if (st == 200) {
            if (body == null || !"OK".equals(code)) {
                String shown = code instanceof String s && !s.isEmpty() ? s : "(空)";
                String why =
                        "HTTP 200 但应答 code 不是 OK（收到 "
                                + shown
                                + "）"
                                + (msg.isEmpty() ? "" : "：" + msg);
                return outcome(FAIL, "RESPONSE_INVALID", cut(why), st, null);
            }
            if (!ping && !batchNo.equals(body.get("batchNo"))) {
                String why =
                        "应答的 batchNo 与批次不符：期望 " + batchNo + "，收到 " + pyStr(body.get("batchNo"));
                return outcome(FAIL, "RESPONSE_MISMATCH", why, st, null);
            }
            return new Outcome(RECEIVED, new LinkedHashMap<>(body), "", "", st, null);
        }
        if (st == 409 && "BATCH_IN_PROGRESS".equals(code)) {
            return outcome(
                    RETRY,
                    "IN_PROGRESS",
                    msg.isEmpty() ? "同一批次正在 MES 处理" : msg,
                    st,
                    retryAfter(resp));
        }
        if (st == 409 && "BATCH_CONTENT_MISMATCH".equals(code)) {
            return outcome(
                    FAIL, "CONTENT_MISMATCH", msg.isEmpty() ? "MES 报同一批次号内容不同" : msg, st, null);
        }
        if (st == 429 || st == 503 || st >= 500) {
            return outcome(
                    RETRY, "HTTP_" + st, msg.isEmpty() ? "HTTP " + st : msg, st, retryAfter(resp));
        }
        if (st == 401 || st == 403) {
            return outcome(FAIL, "AUTH", msg.isEmpty() ? "认证失败（HTTP " + st + "）" : msg, st, null);
        }
        if (st == 404) {
            return outcome(FAIL, "ADDRESS", msg.isEmpty() ? "地址错误（HTTP 404）" : msg, st, null);
        }
        if (st == 413) {
            return outcome(FAIL, "TOO_LARGE", msg.isEmpty() ? "报文过大（HTTP 413）" : msg, st, null);
        }
        if (st == 400) {
            return outcome(
                    FAIL, "BAD_REQUEST", msg.isEmpty() ? "MES 无法解析请求（HTTP 400）" : msg, st, null);
        }
        return outcome(FAIL, "HTTP_" + st, msg.isEmpty() ? "HTTP " + st : msg, st, null);
    }

    private static String cut(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** 与 Python str() 一致：None → "None"。 */
    private static String pyStr(Object v) {
        return v == null ? "None" : String.valueOf(v);
    }
}
