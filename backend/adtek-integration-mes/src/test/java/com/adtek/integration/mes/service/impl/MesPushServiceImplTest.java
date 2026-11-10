package com.adtek.integration.mes.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.adtek.framework.integration.MesPushPort;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** MesPushPort 契约：按应答归类（已收到 / 可重试 / 不重试），不抛异常；HMAC 签名与原系统一致。 */
class MesPushServiceImplTest {
    private HttpServer server;
    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private volatile int status = 200;
    private volatile String reply = "{\"code\":\"OK\",\"batchNo\":\"PS-1\"}";
    private volatile String retryAfter;
    private final MesPushServiceImpl port = new MesPushServiceImpl();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/mes",
                ex -> {
                    seen.put(
                            "body",
                            new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    ex.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), v.get(0)));
                    if (retryAfter != null) {
                        ex.getResponseHeaders().add("Retry-After", retryAfter);
                    }
                    byte[] b = reply.getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(status, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mes";
    }

    private static byte[] gz(String s) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void receivedWithTokenAndRawJson() throws Exception {
        MesPushPort.Outcome o =
                port.send(
                        new MesPushPort.Target(url(), "TOKEN", "t1", 5, false),
                        "PS-1",
                        gz("{\"x\":1}"),
                        2,
                        false);
        assertThat(o.kind()).isEqualTo(MesPushPort.RECEIVED);
        assertThat(seen.get("authorization")).isEqualTo("Bearer t1");
        assertThat(seen.get("body")).isEqualTo("{\"x\":1}");
        assertThat(seen.get("x-sn-attempt")).isEqualTo("2");
    }

    @Test
    void hmacSignatureMatchesTheOriginalAlgorithm() throws Exception {
        port.send(
                new MesPushPort.Target(url(), "HMAC", "k", 5, false),
                "PS-1",
                gz("{\"x\":1}"),
                1,
                false);
        String ts = seen.get("x-sn-timestamp");
        String digest =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest("{\"x\":1}".getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("k".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String want =
                HexFormat.of()
                        .formatHex(
                                mac.doFinal((ts + "\n" + digest).getBytes(StandardCharsets.UTF_8)));
        assertThat(seen.get("x-sn-signature")).isEqualTo(want);
        assertThat(seen.get("authorization")).isNull();
    }

    @Test
    void classifiesFailures() throws Exception {
        MesPushPort.Target t = new MesPushPort.Target(url(), "NONE", "", 5, true);
        status = 503;
        reply = "";
        retryAfter = "30";
        MesPushPort.Outcome o = port.send(t, "PS-1", gz("{}"), 1, false);
        assertThat(o.kind()).isEqualTo(MesPushPort.RETRY);
        assertThat(o.retryAfter()).isEqualTo(30);
        assertThat(seen.get("content-encoding")).isEqualTo("gzip");
        retryAfter = null;
        status = 401;
        assertThat(port.send(t, "PS-1", gz("{}"), 1, false).code()).isEqualTo("AUTH");
        status = 404;
        assertThat(port.send(t, "PS-1", gz("{}"), 1, false).code()).isEqualTo("ADDRESS");
        status = 409;
        reply = "{\"code\":\"BATCH_IN_PROGRESS\"}";
        assertThat(port.send(t, "PS-1", gz("{}"), 1, false).kind()).isEqualTo(MesPushPort.RETRY);
        status = 200;
        reply = "{\"code\":\"OK\",\"batchNo\":\"PS-2\"}";
        assertThat(port.send(t, "PS-1", gz("{}"), 1, false).code()).isEqualTo("RESPONSE_MISMATCH");
        reply = "{\"code\":\"RECEIVED\",\"batchNo\":\"PING-1\"}";
        assertThat(port.send(t, "PING-1", gz("{}"), 1, true).kind()).isEqualTo(MesPushPort.FAIL);
        assertThat(
                        port.send(
                                        new MesPushPort.Target("", "NONE", "", 5, false),
                                        "PS-1",
                                        gz("{}"),
                                        1,
                                        false)
                                .code())
                .isEqualTo("ADDRESS");
    }

    @Test
    void unreachableIsRetryable() throws Exception {
        MesPushPort.Outcome o =
                port.send(
                        new MesPushPort.Target("http://127.0.0.1:1/mes", "NONE", "", 2, false),
                        "PS-1",
                        gz("{}"),
                        1,
                        false);
        assertThat(o.kind()).isEqualTo(MesPushPort.RETRY);
        assertThat(o.code()).isIn("NETWORK", "TIMEOUT");
    }
}
