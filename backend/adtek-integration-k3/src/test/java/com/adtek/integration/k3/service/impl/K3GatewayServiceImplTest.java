package com.adtek.integration.k3.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adtek.framework.integration.K3Exception;
import com.adtek.framework.integration.K3GatewayPort;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** K3GatewayPort 契约：以应用密钥登录后带会话查询；登录失败抛 LOGIN 类异常；字段按原系统归一（中文名、去空白、禁用状态）。 */
class K3GatewayServiceImplTest {
    private HttpServer server;
    private volatile String loginReply = "{\"LoginResultType\":1,\"KDSVCSessionId\":\"S1\"}";
    private final AtomicReference<String> cookie = new AtomicReference<>();
    private final K3GatewayServiceImpl port = new K3GatewayServiceImpl();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/k3cloud/",
                ex -> {
                    ex.getRequestBody().readAllBytes();
                    String path = ex.getRequestURI().getPath();
                    String reply;
                    if (path.endsWith(K3GatewayServiceImpl.LOGIN_SVC)) {
                        reply = loginReply;
                    } else {
                        cookie.set(ex.getRequestHeaders().getFirst("Cookie"));
                        reply =
                                "[[\" 110 \",[{\"Key\":1033,\"Value\":\"VN\"},{\"Key\":2052,\"Value\":\"越南工厂\"}],\"A\"],"
                                        + "[\"120\",\"二厂\",\"B\"],[\"110\",\"重复\",\"A\"]]";
                    }
                    byte[] b = reply.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private K3GatewayPort.Connection conn() {
        return new K3GatewayPort.Connection(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/k3cloud/",
                "acct",
                "app",
                "secret",
                "user",
                2052,
                5,
                5,
                5,
                1,
                0.01);
    }

    @Test
    void fetchesOrganizationsWithTheSession() {
        List<K3GatewayPort.Org> orgs = port.fetchOrganizations(conn());
        assertThat(orgs)
                .containsExactly(
                        new K3GatewayPort.Org("110", "越南工厂", true),
                        new K3GatewayPort.Org("120", "二厂", false));
        assertThat(cookie.get()).contains("kdservice-sessionid=S1");
    }

    @Test
    void loginFailureIsALoginError() {
        loginReply = "{\"LoginResultType\":0,\"Message\":\"密码错误\"}";
        assertThatThrownBy(() -> port.fetchOrganizations(conn()))
                .isInstanceOf(K3Exception.class)
                .hasMessageContaining("密码错误")
                .satisfies(
                        e ->
                                assertThat(((K3Exception) e).kind())
                                        .isEqualTo(K3Exception.Kind.LOGIN));
    }

    @Test
    void numberTextDropsIntegralFraction() {
        assertThat(K3GatewayServiceImpl.numberText(3.0)).isEqualTo("3");
        assertThat(K3GatewayServiceImpl.numberText(" 2.5 ")).isEqualTo("2.5");
        assertThat(K3GatewayServiceImpl.text(null)).isEmpty();
    }
}
