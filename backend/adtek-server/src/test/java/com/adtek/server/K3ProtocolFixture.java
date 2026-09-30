package com.adtek.server;

import com.sun.net.httpserver.*;

import tools.jackson.databind.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 只监听随机回环端口的合成K3协议服务器；不读取真实配置，不伪造浏览器业务API。 返回实际Login/Query/View形状，适配器仍通过HTTP/会话/分页解析。 */
final class K3ProtocolFixture implements AutoCloseable {
    final ObjectMapper json = new ObjectMapper();
    final HttpServer server;
    final ExecutorService executor = Executors.newCachedThreadPool();
    volatile List<List<Object>> orders = List.of();
    volatile List<List<Object>> organizations =
            List.of(List.of("101", "ERP-A", "甲工厂", "A"), List.of("102", "ERP-B", "乙工厂", "A"));
    volatile String failure = "";
    volatile CountDownLatch barrier;
    volatile boolean legacyWire;
    final List<JsonNode> envelopes = new CopyOnWriteArrayList<>();
    final List<Integer> orderOffsets = new CopyOnWriteArrayList<>();

    K3ProtocolFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        orders = List.of();
        organizations =
                List.of(List.of("101", "ERP-A", "甲工厂", "A"), List.of("102", "ERP-B", "乙工厂", "A"));
        failure = "";
        barrier = null;
        legacyWire = false;
        envelopes.clear();
        orderOffsets.clear();
    }

    static List<Object> row(
            String billId,
            String entryId,
            String billNo,
            String customer,
            String pi,
            String orgId,
            String orgCode,
            String orgName,
            String material,
            Object qty) {
        return List.of(
                customer,
                billNo,
                "PO-1",
                pi,
                material,
                "物料名称",
                qty,
                "1",
                "DEMAND-1",
                orgName,
                billId,
                entryId,
                orgId,
                orgCode);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            var params = request.path("parameters");
            String path = exchange.getRequestURI().getPath();
            Object body;
            envelopes.add(request);
            if (failure.equals("http-login") && path.contains("LoginByAppSecret")) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (failure.equals("http-down")) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            if (failure.equals("malformed") || failure.equals("oversize")) {
                byte[] bytes =
                        (failure.equals("malformed") ? "{invalid" : "x".repeat(2048))
                                .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                return;
            }
            if (path.contains("LoginByAppSecret")) {
                body =
                        failure.equals("login")
                                ? Map.of(
                                        "LoginResultType",
                                        0,
                                        "Message",
                                        "SYNTHETIC_SECRET_MUST_NOT_LEAK")
                                : Map.of(
                                        "LoginResultType", 1, "KDSVCSessionId", "isolated-session");
            } else if (!Optional.ofNullable(exchange.getRequestHeaders().getFirst("Cookie"))
                    .orElse("")
                    .matches(".*(?:^|[; ]+)kdservice-sessionid=isolated-session(?:;.*|$)")) {
                exchange.sendResponseHeaders(401, -1);
                return;
            } else if (path.contains("ExecuteBillQuery")) {
                var query = json.readTree(params.get(0).asString());
                boolean org = query.path("FormId").asString().equals("ORG_Organizations");
                int start = query.path("StartRow").asInt(), limit = query.path("Limit").asInt();
                if (!org) orderOffsets.add(start);
                if (!org && start == 0 && barrier != null) {
                    var wait = barrier;
                    wait.countDown();
                    if (!wait.await(8, TimeUnit.SECONDS))
                        throw new IllegalStateException("Fixture barrier timeout");
                }
                if ((Set.of("page", "array-page", "nested-page").contains(failure)
                                && !org
                                && start >= 2)
                        || (Set.of("org", "array-org", "nested-org").contains(failure) && org)) {
                    body =
                            Map.of(
                                    "Result",
                                    Map.of(
                                            "ResponseStatus",
                                            Map.of(
                                                    "IsSuccess",
                                                    false,
                                                    "Errors",
                                                    List.of(
                                                            Map.of(
                                                                    "Message",
                                                                    "SYNTHETIC_SECRET_MUST_NOT_LEAK")))));
                    if (failure.startsWith("array-")) body = List.of(body);
                    if (failure.startsWith("nested-")) body = List.of(List.of(body));
                } else if (failure.equals("timeout") && !org) {
                    Thread.sleep(2500);
                    body = List.of();
                } else {
                    var rows = org ? organizations : orders;
                    body =
                            rows.subList(
                                    Math.min(start, rows.size()),
                                    Math.min(rows.size(), start + limit));
                }
            } else if (path.contains(".View.")) {
                var query = json.readTree(params.get(1).asString());
                String number = query.path("Number").asString();
                var rows = orders.stream().filter(r -> r.get(1).equals(number)).toList();
                if (failure.equals("view"))
                    body =
                            Map.of(
                                    "Result",
                                    Map.of(
                                            "ResponseStatus",
                                            Map.of("IsSuccess", false, "MsgCode", 4)));
                else if (rows.isEmpty())
                    body =
                            Map.of(
                                    "Result",
                                    Map.of(
                                            "ResponseStatus",
                                            Map.of("IsSuccess", false, "MsgCode", 10)));
                else {
                    var first = rows.getFirst();
                    var lines = new ArrayList<Map<String, Object>>();
                    for (var row : rows)
                        lines.add(
                                Map.of(
                                        "Id",
                                        row.get(11),
                                        "MaterialId",
                                        Map.of("Number", row.get(4), "Name", row.get(5)),
                                        "Qty",
                                        row.get(6),
                                        "Status",
                                        row.get(7),
                                        "SaleOrderNo",
                                        row.get(8)));
                    var doc =
                            Map.of(
                                    "Id",
                                    first.get(10),
                                    "BillNo",
                                    number,
                                    "F_ora_Base",
                                    Map.of("Number", first.get(0)),
                                    "F_HX_PO",
                                    first.get(2),
                                    "F_HX_PI",
                                    first.get(3),
                                    "PrdOrgId",
                                    Map.of(
                                            "Id",
                                            first.get(12),
                                            "Number",
                                            first.get(13),
                                            "Name",
                                            first.get(9)),
                                    "TreeEntity",
                                    lines);
                    body =
                            Map.of(
                                    "Result",
                                    Map.of(
                                            "ResponseStatus",
                                            Map.of("IsSuccess", true),
                                            "Result",
                                            doc));
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes =
                    json.writeValueAsBytes(legacyWire ? json.writeValueAsString(body) : body);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
