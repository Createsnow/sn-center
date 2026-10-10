package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.*;

import java.net.*;
import java.net.http.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** S6真实SN业务痕迹/双边工厂权限/归档同读/固定导出与审计事务回归，手动驱动工作器注入故障；不连接真实ERP。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SnAuditIT {
    static final String SECRET = HexFormat.of().formatHex(new SecureRandom().generateSeed(32)),
            PASSWORD = "SnStage9!" + SECRET.substring(0, 24);
    static final K3ProtocolFixture ERP = new K3ProtocolFixture();

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_sn_stage6_audit_it")
                    .withUsername("stage3")
                    .withPassword(SECRET)
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_as_cs",
                            "--default-time-zone=+00:00");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.4.7-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", SECRET);

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry p) {
        p.add("ADTEK_DB_URL", MYSQL::getJdbcUrl);
        p.add("ADTEK_DB_USER", MYSQL::getUsername);
        p.add("ADTEK_DB_PASSWORD", MYSQL::getPassword);
        p.add("ADTEK_MIGRATION_USER", MYSQL::getUsername);
        p.add("ADTEK_MIGRATION_PASSWORD", MYSQL::getPassword);
        p.add("ADTEK_REDIS_HOST", REDIS::getHost);
        p.add("ADTEK_REDIS_PORT", () -> REDIS.getMappedPort(6379));
        p.add("ADTEK_REDIS_PASSWORD", () -> SECRET);
        p.add("ADTEK_JWT_SECRET", () -> SECRET);
        p.add("adtek.k3.enabled", () -> true);
        p.add("adtek.k3.url", ERP::url);
        p.add("adtek.k3.account", () -> "ISOLATED");
        p.add("adtek.k3.app-id", () -> "fixture");
        p.add("adtek.k3.app-secret", () -> SECRET);
        p.add("adtek.k3.username", () -> "fixture");
        p.add("adtek.k3.page-size", () -> 2);
        p.add("adtek.k3.max-pages", () -> 10);
        p.add("adtek.k3.max-rows", () -> 18);
        p.add("adtek.k3.query-seconds", () -> 10);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @Autowired com.adtek.framework.task.service.TaskExecutionService worker;
    @Autowired com.adtek.framework.task.mapper.DurableTaskMapper tasks;

    @AfterAll
    static void close() {
        ERP.close();
    }

    @BeforeEach
    void reset() {
        ERP.reset();
        for (String table :
                List.of(
                        "sn_audit_identity",
                        "sn_audit_export_row",
                        "sn_audit_export",
                        "sn_audit_archive",
                        "sn_audit",
                        "sn_query_export_row",
                        "sn_query_export",
                        "sn_transfer_work",
                        "sn_transfer_member",
                        "sn_transfer",
                        "sn_acquire_result",
                        "sn_acquire_member",
                        "sn_acquire_batch",
                        "sn_acquire_work",
                        "sn_print_member",
                        "sn_print",
                        "sn_acquire_exception",
                        "sys_service_permission",
                        "sys_service_client",
                        "sn_key",
                        "sn_item",
                        "sn_work",
                        "sn_preview",
                        "sn_pi_counter",
                        "sn_pi_rule_choice",
                        "sys_task_progress",
                        "sys_file",
                        "sys_task",
                        "sys_operation",
                        "sn_bill_generation",
                        "sn_source_entry_quota",
                        "sn_order_entry",
                        "sn_order_bill",
                        "sn_order_sync",
                        "sn_org_mapping",
                        "sn_factory",
                        "sn_rule_version",
                        "sn_rule",
                        "sn_user_factory",
                        "sys_user_role",
                        "sys_user",
                        "sys_oper_log")) jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id>=1000");
        jdbc.update("DELETE FROM sys_role WHERE id>=1000");
        jdbc.update("UPDATE sn_snapshot_control SET revision=0");
        jdbc.execute("ALTER TABLE sn_factory AUTO_INCREMENT=9007199254742001");
        jdbc.execute("ALTER TABLE sn_order_bill AUTO_INCREMENT=9007199254743001");
        jdbc.execute("ALTER TABLE sn_rule AUTO_INCREMENT=9007199254744001");
        try (var c = redis.getConnectionFactory().getConnection()) {
            c.serverCommands().flushDb();
        }
        user(9007199254741001L, "sn_hq", 2);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(9007199254741001,1)");
    }

    void user(long id, String username, long role) {
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES(?,?,?,?,1,FALSE)",
                id,
                username,
                passwords.encode(PASSWORD),
                username);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", id, role);
    }

    List<Object> row(String bill, String entry, String no, String material, Object qty) {
        return K3ProtocolFixture.row(
                bill, entry, no, "C1", "PI-1", "101", "ERP-A", "甲工厂", material, qty);
    }

    void sample() {
        ERP.orders =
                List.of(
                        row("9007199254740993", "11", "MO-1", "M1", "8"),
                        row("9007199254740993", "12", "MO-1", "M1", "3"),
                        row("9007199254740993", "13", "MO-1", "", "2"),
                        K3ProtocolFixture.row(
                                "2", "21", "MO-2", "C2", "PI-2", "102", "ERP-B", "乙工厂", "M2", "5"));
    }

    JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
        return json.readTree(response.body()).path("data");
    }

    Map<String, Object> rule(String code, String scope, String value, String prefix) {
        return new HashMap<>(
                Map.of(
                        "code",
                        code,
                        "name",
                        code,
                        "bindScope",
                        scope,
                        "bindValue",
                        value,
                        "prefix",
                        prefix,
                        "suffix",
                        "",
                        "base",
                        10,
                        "length",
                        4,
                        "alphabet",
                        ""));
    }

    String uuid() {
        return UUID.randomUUID().toString();
    }

    Map<String, Object> init(String operation, String pi, String start, List<String> sns) {
        var out = new HashMap<String, Object>();
        out.put("operationId", operation);
        out.put("pi", pi);
        out.put("start", start);
        out.put("sns", sns);
        return out;
    }

    String prepared(Browser b) throws Exception {
        sample();
        data(b.post("/orders/sync", Map.of()));
        data(b.post("/rules", rule("GENERAL", "GENERAL", "", "G")));
        return jdbc.queryForObject(
                "SELECT CAST(id AS CHAR) FROM sn_order_bill WHERE bill_no='MO-1'", String.class);
    }

    String preview(Browser b, String bill, String qty) throws Exception {
        return data(b.post(
                        "/generation/previews",
                        Map.of(
                                "billId",
                                bill,
                                "quantity",
                                qty,
                                "ruleId",
                                jdbc.queryForObject(
                                        "SELECT CAST(id AS CHAR) FROM sn_rule WHERE bind_scope='GENERAL'",
                                        String.class))))
                .path("id")
                .asString();
    }

    String generate(Browser b, String preview) throws Exception {
        return data(b.post("/generation", Map.of("operationId", uuid(), "previewId", preview)))
                .path("taskId")
                .asString();
    }

    void run(String id) throws Exception {
        var lease = worker.claim(uuid());
        assertThat(lease.id()).isEqualTo(id);
        worker.execute(lease);
    }

    int fail(String id) throws Exception {
        var lease = worker.claim(uuid());
        assertThat(lease.id()).isEqualTo(id);
        Exception failure = catchThrowableOfType(Exception.class, () -> worker.execute(lease));
        assertThat(failure).isNotNull();
        worker.failed(lease, failure);
        return jdbc.queryForObject("SELECT error_code FROM sys_task WHERE id=?", Integer.class, id);
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    long last(String pi) {
        return jdbc.queryForObject("SELECT last_seq FROM sn_pi_counter WHERE pi=?", Long.class, pi);
    }

    @Autowired com.adtek.framework.task.TaskSettings taskSettings;

    String allocated(Browser b) throws Exception {
        String bill = prepared(b);
        run(generate(b, preview(b, bill, "13")));
        run(
                data(b.post(
                                "/allocations",
                                Map.of(
                                        "operationId",
                                        uuid(),
                                        "billId",
                                        bill,
                                        "expectedQuantity",
                                        "13")))
                        .path("taskId")
                        .asString());
        return jdbc.queryForObject(
                "SELECT CAST(factory_id AS CHAR) FROM sn_order_bill WHERE id=?",
                String.class,
                bill);
    }

    Map<String, Object> take(String operation, String factory, String pi) {
        return Map.of("operationId", operation, "factoryId", factory, "pi", pi);
    }

    String acquire(Browser b, String factory) throws Exception {
        String task =
                data(b.post("/acquisitions", take(uuid(), factory, "PI-1")))
                        .path("taskId")
                        .asString();
        run(task);
        return task;
    }

    List<JsonNode> members(Browser b, String batch) throws Exception {
        var out = new ArrayList<JsonNode>();
        String cursor = null;
        do {
            var page =
                    data(
                            b.get(
                                    "/acquire-batches/"
                                            + batch
                                            + "/items?size=5"
                                            + (cursor == null
                                                    ? ""
                                                    : "&cursor="
                                                            + URLEncoder.encode(
                                                                    cursor,
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8))));
            page.path("items").forEach(out::add);
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
        } while (cursor != null);
        return out;
    }

    void drain() throws Exception {
        for (int i = 0; i < 10; i++) {
            var lease = worker.claim(uuid());
            if (lease == null) return;
            worker.execute(lease);
        }
        throw new IllegalStateException("Unexpected task backlog");
    }

    byte[] download(Browser b, String file) throws Exception {
        var response =
                b.client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/v1/files/"
                                                        + file))
                                .header("Authorization", "Bearer " + b.token)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    String target(String source) {
        return jdbc.queryForObject(
                "SELECT CAST(id AS CHAR) FROM sn_factory WHERE id<>? ORDER BY id LIMIT 1",
                String.class,
                source);
    }

    String export(Browser b, String format, Map<String, Object> filters) throws Exception {
        var task =
                data(
                        b.post(
                                "/exports",
                                Map.of(
                                        "operationId",
                                        uuid(),
                                        "format",
                                        format,
                                        "filters",
                                        filters)));
        assertThat(task.path("operationState").asString()).isEqualTo("SUCCEEDED");
        return task.path("taskId").asString();
    }

    List<JsonNode> items(Browser b, String params) throws Exception {
        var out = new ArrayList<JsonNode>();
        String cursor = null;
        do {
            var page =
                    data(
                            b.get(
                                    "/items?size=5&"
                                            + params
                                            + (cursor == null ? "" : "&cursor=" + cursor)));
            page.path("items").forEach(out::add);
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
        } while (cursor != null);
        return out;
    }

    @Autowired com.adtek.sn.service.support.SnWorkSettings snSettings;

    String auditExport(Browser b, String format, Map<String, Object> filter) throws Exception {
        return data(b.post(
                        "/audits/exports",
                        Map.of("operationId", uuid(), "format", format, "filters", filter)))
                .path("taskId")
                .asString();
    }

    List<JsonNode> audits(Browser b, String params) throws Exception {
        var out = new ArrayList<JsonNode>();
        String cursor = null;
        do {
            var page =
                    data(
                            b.get(
                                    "/audits?size=3&"
                                            + params
                                            + (cursor == null ? "" : "&cursor=" + cursor)));
            page.path("items").forEach(out::add);
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
        } while (cursor != null);
        return out;
    }

    String transfer(Browser b, String source, String target, String pi, String mode)
            throws Exception {
        String task =
                data(b.post(
                                mode.equals("DIRECT") ? "/transfers/direct" : "/transfers",
                                Map.of(
                                        "operationId",
                                        uuid(),
                                        "factoryId",
                                        source,
                                        "pi",
                                        "PI-1",
                                        "scope",
                                        "PI",
                                        "toFactoryId",
                                        target,
                                        "toPi",
                                        pi,
                                        "targetSource",
                                        "MANUAL",
                                        "reason",
                                        "交接%_记录")))
                        .path("taskId")
                        .asString();
        run(task);
        return jdbc.queryForObject(
                "SELECT transfer_id FROM sn_transfer_work WHERE task_id=?", String.class, task);
    }

    String detailPath(JsonNode row) {
        return "/audits/"
                + row.path("id").asString()
                + "?createdAt="
                + row.path("createdAt").asString();
    }

    @Test
    void businessEventsKeepExactSnapshotIdentityAndStatusesAfterRenames() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b), batch = acquire(b, factory);
            var generated = audits(b, "action=sn:generation:execute").getFirst();
            assertThat(generated.path("afterStatus").asString()).isEqualTo("PENDING_ALLOC");
            assertThat(generated.path("material").isNull()).isTrue();
            var allocated = audits(b, "action=sn:allocation:execute").getFirst();
            assertThat(allocated.path("billNo").asString()).isEqualTo("MO-1");
            assertThat(allocated.path("beforeStatus").asString()).isEqualTo("PENDING_ALLOC");
            assertThat(allocated.path("afterStatus").asString()).isEqualTo("TO_ACQUIRE");
            data(b.post("/prints", take(uuid(), factory, "PI-1")));
            drain();
            var before = audits(b, "action=sn:acquire:take&result=SUCCESS");
            assertThat(before).hasSize(1);
            var event = before.getFirst();
            assertThat(event.path("factoryId").asString()).isEqualTo(factory);
            assertThat(event.path("actorId").asString()).isEqualTo("9007199254741001");
            assertThat(event.path("quantity").asString()).isEqualTo("13");
            assertThat(event.path("startSn").asString()).isEqualTo("G0001");
            assertThat(event.path("endSn").asString()).isEqualTo("G0013");
            assertThat(event.path("batchId").asString()).isEqualTo(batch);
            String batchNo =
                    jdbc.queryForObject(
                            "SELECT batch_no FROM sn_acquire_batch WHERE id=?",
                            String.class,
                            batch);
            assertThat(event.path("batchNo").asString()).isEqualTo(batchNo);
            assertThat(audits(b, "batchId=" + batchNo))
                    .extracting(row -> row.path("id").asString())
                    .contains(event.path("id").asString());
            assertThat(event.path("beforeStatus").asString()).isEqualTo("TO_ACQUIRE");
            assertThat(event.path("afterStatus").asString()).isEqualTo("TO_PRINT");
            assertThat(event.path("material").isNull()).isTrue();
            assertThat(event.path("roles").asString()).contains("SN_HQ");
            jdbc.update("UPDATE sys_user SET display_name='新姓名' WHERE username='sn_hq'");
            jdbc.update("UPDATE sn_factory SET name=CONCAT('新名-',id)");
            jdbc.update("UPDATE sn_item SET factory_id=?,pi='AFTER'", target(factory));
            assertThat(data(b.get(detailPath(event)))).isEqualTo(event);
            var print = audits(b, "action=sn:print:execute").getFirst();
            assertThat(print.path("beforeStatus").asString()).isEqualTo("TO_PRINT");
            assertThat(print.path("afterStatus").asString()).isEqualTo("PRINTED");
            assertThat(print.path("quantity").asString()).isEqualTo("13");
            assertThat(data(b.get("/audits/meta")).path("permanent").asBoolean()).isTrue();
        }
    }

    @Test
    void transferHistoryIsVisibleToBothOriginalFactoriesButNoGlobalFactsLeak() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String source = allocated(b),
                    target = target(source),
                    transfer = transfer(b, source, target, "PI-2", "APPLY");
            String operation = uuid();
            String task =
                    data(b.post(
                                    "/transfers/" + transfer + "/approve",
                                    Map.of(
                                            "operationId",
                                            operation,
                                            "version",
                                            "1",
                                            "note",
                                            "已交接")))
                            .path("taskId")
                            .asString();
            run(task);
            var event = audits(b, "action=sn:transfer:approve").getFirst();
            assertThat(event.path("operationId").asString()).isEqualTo(operation);
            assertThat(event.path("beforeStatus").asString()).isEqualTo("APPLYING");
            assertThat(event.path("afterStatus").asString()).isEqualTo("TO_ACQUIRE");
            assertThat(event.path("detail").asString()).contains("已交接");
            for (int index = 0; index < 3; index++) {
                long user = 11 + index;
                user(user, "reader" + index, 5);
                if (index < 2)
                    jdbc.update(
                            "INSERT INTO sn_user_factory(user_id,factory_id) VALUES(?,?)",
                            user,
                            index == 0 ? source : target);
                try (var reader = new Browser("reader" + index)) {
                    var rows = audits(reader, "action=sn:transfer:approve");
                    assertThat(rows.size()).isEqualTo(index < 2 ? 1 : 0);
                    var numbered = data(reader.get("/audits/page?action=sn:transfer:approve"));
                    assertThat(numbered.path("total").asString()).isEqualTo(index < 2 ? "1" : "0");
                    assertThat(numbered.path("items").size()).isEqualTo(index < 2 ? 1 : 0);
                    assertThat(
                                    data(reader.get("/audits/page?action=sn:order:sync"))
                                            .path("total")
                                            .asString())
                            .isEqualTo("0");
                    assertThat(reader.get(detailPath(event)).statusCode())
                            .isEqualTo(index < 2 ? 200 : 404);
                    assertThat(audits(reader, "action=sn:order:sync")).isEmpty();
                    assertThat(
                                    reader.get(
                                                    "/audits?factoryId="
                                                            + (index == 0 ? target : source))
                                            .statusCode())
                            .isEqualTo(403);
                }
            }
        }
    }

    @Test
    void onlineArchiveAndMergedReadsUseSameFiltersAndNeverDuplicateStagedCopies() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String source = allocated(b);
            acquire(b, source);
            long original = count("sn_audit");
            jdbc.update("INSERT INTO sn_audit_archive SELECT * FROM sn_audit");
            assertThat(audits(b, "storage=ONLINE")).hasSize((int) original);
            assertThat(audits(b, "storage=ARCHIVE")).hasSize((int) original);
            assertThat(audits(b, "storage=ALL")).hasSize((int) original);
            jdbc.update("DELETE FROM sn_audit WHERE action='sn:acquire:take'");
            assertThat(audits(b, "action=sn:acquire:take&storage=ONLINE")).isEmpty();
            assertThat(audits(b, "action=sn:acquire:take&storage=ARCHIVE")).hasSize(1);
            var row =
                    audits(
                                    b,
                                    "action=sn:acquire:take&source=PAGE&pi=PI-1&operator=sn_hq&result=SUCCESS")
                            .getFirst();
            assertThat(data(b.get(detailPath(row)))).isEqualTo(row);
            assertThat(data(b.get("/audits/count?storage=ALL")).path("quantity").asString())
                    .isEqualTo(Long.toString(original));
            assertThat(b.get("/audits?storage=sn_item").statusCode()).isEqualTo(400);
            assertThat(b.get("/audits?dateFrom=2026-10-01&dateTo=2026-09-01").statusCode())
                    .isEqualTo(400);
            assertThat(audits(b, "dateFrom=2000-01-01&dateTo=2000-01-02")).isEmpty();
        }
    }

    @Test
    void numberedPagesKeepExactTotalsOrderArchiveDeduplicationAndFilterScope() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b);
            acquire(b, factory);
            var all = audits(b, "storage=ALL");
            assertThat(all.size()).isGreaterThan(2);
            jdbc.update("INSERT INTO sn_audit_archive SELECT * FROM sn_audit");
            var first = data(b.get("/audits/page?size=2"));
            var second = data(b.get("/audits/page?page=2&size=2"));
            assertThat(first.path("total").asString()).isEqualTo(Integer.toString(all.size()));
            assertThat(second.path("total")).isEqualTo(first.path("total"));
            assertThat(first.path("items").get(0)).isEqualTo(all.get(0));
            assertThat(first.path("items").get(1)).isEqualTo(all.get(1));
            assertThat(second.path("items").get(0)).isEqualTo(all.get(2));
            assertThat(data(b.get("/audits/page?page=2147483647&size=1000")).path("items").size())
                    .isZero();
            for (String storage : List.of("ALL", "ONLINE", "ARCHIVE")) {
                var filtered =
                        data(
                                b.get(
                                        "/audits/page?action=sn:acquire:take&result=SUCCESS&operator=sn_hq&source=PAGE&pi=PI-1&storage="
                                                + storage));
                assertThat(filtered.path("total").asString()).isEqualTo("1");
                assertThat(filtered.path("items").get(0))
                        .isEqualTo(audits(b, "action=sn:acquire:take").getFirst());
            }
            assertThat(data(b.get("/audits/page?keyword=%25_")).path("total").asString())
                    .isEqualTo("0");
            for (String invalid :
                    List.of(
                            "page=0",
                            "size=0",
                            "size=1001",
                            "dateFrom=2026-10-01&dateTo=2026-09-01"))
                assertThat(b.get("/audits/page?" + invalid).statusCode()).isEqualTo(400);
        }
    }

    @Test
    void cursorsBindActorFiltersAndAuthorizationAndKeywordEscapesWildcards() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String source = allocated(b);
            transfer(b, source, target(source), "PI-2", "DIRECT");
            String cursor = data(b.get("/audits?size=1")).path("nextCursor").asString();
            assertThat(b.get("/audits?cursor=" + cursor + "x").statusCode()).isEqualTo(400);
            assertThat(b.get("/audits?storage=ONLINE&cursor=" + cursor).statusCode())
                    .isEqualTo(400);
            user(11, "other_admin", 2);
            try (var other = new Browser("other_admin")) {
                assertThat(other.get("/audits?cursor=" + cursor).statusCode()).isEqualTo(400);
            }
            String keyword = URLEncoder.encode("%_", java.nio.charset.StandardCharsets.UTF_8);
            assertThat(audits(b, "keyword=" + keyword)).isEmpty(); // 自由原因在专门列；关键词按旧约定只查编号/详情。
            assertThat(b.get("/audits?size=1001").statusCode()).isEqualTo(400);
            assertThat(b.get("/audits?result=UNKNOWN").statusCode()).isEqualTo(400);
        }
    }

    @Test
    void successProjectionFailureRollsBackBusinessAndFailureIsRecordedIndependently()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String source = allocated(b);
            String id =
                    data(b.post("/acquisitions", take(uuid(), source, "PI-1")))
                            .path("taskId")
                            .asString();
            jdbc.execute(
                    "ALTER TABLE sn_audit ADD CONSTRAINT ck_s6_audit_failure"
                            + " CHECK(action<>'sn:acquire:take' OR result='FAILED')");
            try {
                fail(id);
            } finally {
                jdbc.execute("ALTER TABLE sn_audit DROP CHECK ck_s6_audit_failure");
            }
            assertThat(count("sn_acquire_member")).isZero();
            assertThat(count("sn_acquire_batch")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE'",
                                    Long.class))
                    .isEqualTo(13);
            var failed = audits(b, "action=sn:acquire:take&result=FAILED");
            assertThat(failed).hasSize(1);
            assertThat(failed.getFirst().path("quantity").asString()).isEqualTo("0");
            assertThat(failed.getFirst().path("afterStatus").isNull()).isTrue();
            assertThat(failed.getFirst().path("errorCode").asString()).matches("[0-9]{10}");
            assertThat(failed.getFirst().path("detail").asString())
                    .doesNotContain("password", "constraint", "INSERT");
        }
    }

    @Test
    void csvAndXlsxAreFixedTextSnapshotsAndRecoverByteIdenticallyAfterArchive() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String source = allocated(b);
            jdbc.update("UPDATE sys_user SET display_name='=2+2' WHERE username='sn_hq'");
            acquire(b, source);
            String csv = auditExport(b, "CSV", Map.of("action", "sn:acquire:take")),
                    xlsx = auditExport(b, "XLSX", Map.of("action", "sn:acquire:take"));
            jdbc.update("INSERT INTO sn_audit_archive SELECT * FROM sn_audit");
            jdbc.update("DELETE FROM sn_audit");
            jdbc.update("UPDATE sys_user SET display_name='AFTER' WHERE username='sn_hq'");
            drain();
            byte[] csvBytes = download(b, csv), xlsxBytes = download(b, xlsx);
            assertThat(new String(csvBytes, java.nio.charset.StandardCharsets.UTF_8))
                    .contains("'=2+2", "G0001", "G0013")
                    .doesNotContain("AFTER");
            try (var wb =
                    org.apache.poi.ss.usermodel.WorkbookFactory.create(
                            new java.io.ByteArrayInputStream(xlsxBytes))) {
                assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(1);
                assertThat(wb.getSheetAt(0).getRow(1).getCell(2).getStringCellValue())
                        .isEqualTo("=2+2");
                assertThat(wb.getSheetName(0)).isEqualTo("audit");
                assertThat(wb.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short) 24);
                assertThat(wb.getSheetAt(0).getRow(1).getCell(14).getNumericCellValue())
                        .isEqualTo(13);
                assertThat(wb.getSheetAt(0).getRow(1).getCell(17).getStringCellValue())
                        .isEqualTo(
                                jdbc.queryForObject(
                                        "SELECT batch_no FROM sn_acquire_batch", String.class));
                assertThat(wb.getSheetAt(0).getRow(1).getCell(3).getStringCellValue())
                        .isEqualTo("admin");
                assertThat(wb.getSheetAt(0).getRow(1).getCell(5).getStringCellValue())
                        .isEqualTo("SN_ACQUIRE");
                assertThat(wb.getSheetAt(0).getRow(1).getCell(6).getStringCellValue())
                        .isEqualTo("OK");
                for (var row : wb.getSheetAt(0))
                    for (var cell : row)
                        assertThat(cell.getCellType())
                                .isEqualTo(
                                        row.getRowNum() > 0 && cell.getColumnIndex() == 14
                                                ? org.apache.poi.ss.usermodel.CellType.NUMERIC
                                                : org.apache.poi.ss.usermodel.CellType.STRING);
            }
            for (String id : List.of(csv, xlsx)) {
                var root =
                        java.nio.file.Path.of(taskSettings.fileRoot()).toAbsolutePath().normalize();
                var file =
                        root.resolve("objects")
                                .resolve(id + (id.equals(csv) ? ".csv" : ".xlsx"))
                                .normalize();
                assertThat(file.startsWith(root)).isTrue();
                java.nio.file.Files.delete(file);
                assertThat(b.raw("GET", "/files/" + id, null, b.token, null).statusCode())
                        .isEqualTo(409);
                data(b.raw("POST", "/tasks/" + id + "/recover", Map.of(), b.token, b.csrf));
                drain();
                assertThat(download(b, id)).isEqualTo(id.equals(csv) ? csvBytes : xlsxBytes);
            }
            assertThat(count("sn_audit_export")).isEqualTo(2);
            assertThat(count("sn_audit_export_row")).isEqualTo(2);
        }
    }

    @Test
    void creationIdentityOrdersEqualTimesAndExportIndependentlyOfTimestampsAndArchive()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            allocated(b);
            var template =
                    jdbc.queryForMap(
                            "SELECT * FROM sn_audit WHERE action='sn:generation:execute' LIMIT 1");
            for (int n = 1; n <= 3; n++) {
                var row = new LinkedHashMap<>(template);
                String id = String.format("32000000-0000-4000-8000-%012d", 4 - n);
                row.put("id", id);
                row.put("pi", "ORDER-TEST");
                row.put("start_sn", "ORDER" + n);
                row.put(
                        "created_at",
                        java.sql.Timestamp.valueOf(
                                n == 3 ? "2026-10-08 01:00:09" : "2026-10-08 01:00:10"));
                jdbc.update(
                        "INSERT INTO sn_audit("
                                + String.join(",", row.keySet())
                                + ") VALUES("
                                + String.join(",", Collections.nCopies(row.size(), "?"))
                                + ")",
                        row.values().toArray());
                jdbc.update(
                        "INSERT INTO sn_audit_identity(audit_id,created_at) VALUES(?,?)",
                        id,
                        row.get("created_at"));
            }
            for (int archive = 0; archive < 2; archive++) {
                var page =
                        data(
                                b.get(
                                        "/audits/page?pi=ORDER-TEST&dateFrom=2026-10-08&dateTo=2026-10-08"));
                assertThat(page.path("total").asString()).isEqualTo("3");
                assertThat(
                                page.path("items")
                                        .valueStream()
                                        .map(r -> r.path("startSn").asString())
                                        .toList())
                        .containsExactly("ORDER2", "ORDER1", "ORDER3");
                String export =
                        auditExport(
                                b,
                                "XLSX",
                                Map.of(
                                        "pi",
                                        "ORDER-TEST",
                                        "dateFrom",
                                        "2026-10-08",
                                        "dateTo",
                                        "2026-10-08"));
                drain();
                try (var wb =
                        org.apache.poi.ss.usermodel.WorkbookFactory.create(
                                new java.io.ByteArrayInputStream(download(b, export)))) {
                    assertThat(wb.getSheetAt(0).getRow(1).getCell(12).getStringCellValue())
                            .isEqualTo("ORDER3");
                    assertThat(wb.getSheetAt(0).getRow(2).getCell(12).getStringCellValue())
                            .isEqualTo("ORDER2");
                    assertThat(wb.getSheetAt(0).getRow(3).getCell(12).getStringCellValue())
                            .isEqualTo("ORDER1");
                }
                if (archive == 0) {
                    jdbc.update(
                            "INSERT INTO sn_audit_archive SELECT * FROM sn_audit WHERE pi='ORDER-TEST'");
                    jdbc.update("DELETE FROM sn_audit WHERE pi='ORDER-TEST'");
                }
            }
        }
    }

    @Test
    void existingVersionOneSnapshotStillWritesAndRecoversTwentyEightTextColumns() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String source = allocated(b);
            acquire(b, source);
            for (String format : List.of("CSV", "XLSX")) {
                String id = auditExport(b, format, Map.of("action", "sn:acquire:take"));
                // 模拟V33持久行；不通过新版投影重新构造旧文件，工作器只能读取固定版本和固定28列。
                var legacy = new ArrayList<>(Collections.nCopies(28, ""));
                legacy.set(0, "2026-10-08T01:02:03.000000Z");
                legacy.set(2, "=2+2");
                legacy.set(12, "G0001");
                legacy.set(14, "13");
                legacy.set(23, "{\"fixed\": true}");
                jdbc.update("UPDATE sn_audit_export SET format_version=1 WHERE task_id=?", id);
                jdbc.update(
                        "UPDATE sn_audit_export_row SET cells=? WHERE task_id=?",
                        json.writeValueAsString(legacy),
                        id);
                String fixed =
                        jdbc.queryForObject(
                                "SELECT CAST(cells AS CHAR) FROM sn_audit_export_row WHERE task_id=?",
                                String.class,
                                id);
                drain();
                byte[] before = download(b, id);
                if (format.equals("XLSX")) {
                    try (var wb =
                            org.apache.poi.ss.usermodel.WorkbookFactory.create(
                                    new java.io.ByteArrayInputStream(before))) {
                        assertThat(wb.getSheetName(0)).isEqualTo("SN");
                        assertThat(wb.getSheetAt(0).getRow(0).getLastCellNum())
                                .isEqualTo((short) 28);
                        assertThat(wb.getSheetAt(0).getRow(1).getCell(14).getCellType())
                                .isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
                    }
                } else
                    assertThat(new String(before, java.nio.charset.StandardCharsets.UTF_8))
                            .contains("'=2+2", "痕迹ID");
                var root =
                        java.nio.file.Path.of(taskSettings.fileRoot()).toAbsolutePath().normalize();
                var file =
                        root.resolve("objects")
                                .resolve(id + "." + format.toLowerCase(Locale.ROOT))
                                .normalize();
                assertThat(file.startsWith(root)).isTrue();
                java.nio.file.Files.delete(file);
                assertThat(b.raw("GET", "/files/" + id, null, b.token, null).statusCode())
                        .isEqualTo(409);
                data(b.raw("POST", "/tasks/" + id + "/recover", Map.of(), b.token, b.csrf));
                drain();
                assertThat(download(b, id)).isEqualTo(before);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT CAST(cells AS CHAR) FROM sn_audit_export_row WHERE task_id=?",
                                        String.class,
                                        id))
                        .isEqualTo(fixed);
            }
        }
    }

    @Test
    void originalFactoryRangeAndOwnerAreRecheckedForFilesAndQueuedWork() throws Exception {
        try (var hq = new Browser("sn_hq")) {
            String source = allocated(hq), other = target(source);
            acquire(hq, source);
            user(11, "local", 5);
            jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)", source);
            String id;
            try (var local = new Browser("local")) {
                id = auditExport(local, "CSV", Map.of("action", "sn:acquire:take"));
                drain();
                assertThat(download(local, id)).isNotEmpty();
                assertThat(hq.raw("GET", "/files/" + id, null, hq.token, null).statusCode())
                        .isEqualTo(404);
            }
            jdbc.update("UPDATE sn_item SET factory_id=?", other);
            jdbc.update("UPDATE sn_user_factory SET factory_id=? WHERE user_id=11", other);
            jdbc.update("UPDATE sys_user SET security_version=security_version+1 WHERE id=11");
            try (var changed = new Browser("local")) {
                assertThat(
                                changed.raw("GET", "/files/" + id, null, changed.token, null)
                                        .statusCode())
                        .isEqualTo(403);
                assertThat(changed.get("/audits/exports").statusCode()).isEqualTo(403);
            }
        }
    }

    @Test
    void exportLimitsAndAuditFailureRollbackSnapshotAndOperationTogether() throws Exception {
        try (var b = new Browser("sn_hq")) {
            allocated(b);
            long operations = count("sys_operation");
            int old = snSettings.getMaxAuditExportQuantity();
            try {
                snSettings.setMaxAuditExportQuantity(1);
                assertThat(
                                b.post(
                                                "/audits/exports",
                                                Map.of(
                                                        "operationId",
                                                        uuid(),
                                                        "format",
                                                        "CSV",
                                                        "filters",
                                                        Map.of()))
                                        .statusCode())
                        .isEqualTo(409);
            } finally {
                snSettings.setMaxAuditExportQuantity(old);
            }
            assertThat(count("sn_audit_export_row")).isZero();
            assertThat(count("sn_audit_export")).isZero();
            assertThat(count("sys_operation")).isEqualTo(operations);
            jdbc.execute(
                    "ALTER TABLE sn_audit ADD CONSTRAINT ck_s6_export_audit"
                            + " CHECK(action<>'sn:audit:export:submit')");
            try {
                assertThat(
                                b.post(
                                                "/audits/exports",
                                                Map.of(
                                                        "operationId",
                                                        uuid(),
                                                        "format",
                                                        "CSV",
                                                        "filters",
                                                        Map.of()))
                                        .statusCode())
                        .isGreaterThanOrEqualTo(500);
            } finally {
                jdbc.execute("ALTER TABLE sn_audit DROP CHECK ck_s6_export_audit");
            }
            assertThat(count("sn_audit_export")).isZero();
            assertThat(count("sn_audit_export_row")).isZero();
            assertThat(count("sys_operation")).isEqualTo(operations);
        }
    }

    @Test
    void cancellingQueuedGenerationRecordsNoCompletedSnTransition() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            String id = generate(b, preview(b, bill, "13"));
            data(b.raw("POST", "/tasks/" + id + "/cancel", Map.of(), b.token, b.csrf));
            var event = audits(b, "action=sn:generation:execute:cancel").getFirst();
            assertThat(event.path("quantity").asString()).isEqualTo("0");
            assertThat(event.path("afterStatus").isNull()).isTrue();
            assertThat(count("sn_item")).isZero();
        }
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.adtek.sn.service.support.SnAuditDates auditDates;

    @Test
    void relativeDateExportRetryAfterMidnightKeepsOriginalSnapshot() throws Exception {
        try (var b = new Browser("sn_hq")) {
            allocated(b);
            String operation = uuid();
            var command =
                    Map.of(
                            "operationId",
                            operation,
                            "format",
                            "CSV",
                            "filters",
                            Map.of("action", "sn:generation:execute"));
            String id = data(b.post("/audits/exports", command)).path("taskId").asString();
            String filters =
                    jdbc.queryForObject(
                            "SELECT CAST(filters AS CHAR) FROM sn_audit_export WHERE task_id=?",
                            String.class,
                            id);
            var today = auditDates.today();
            try {
                org.mockito.Mockito.doReturn(today.plusDays(1)).when(auditDates).today();
                assertThat(data(b.post("/audits/exports", command)).path("taskId").asString())
                        .isEqualTo(id);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT CAST(filters AS CHAR) FROM sn_audit_export WHERE"
                                                + " task_id=?",
                                        String.class,
                                        id))
                        .isEqualTo(filters);
                assertThat(count("sn_audit_export")).isEqualTo(1);
            } finally {
                org.mockito.Mockito.doCallRealMethod().when(auditDates).today();
            }
        }
    }

    @Test
    void realRuleChangesKeepPlainDetailsNullQuantitySeparateActionsAndArchiveContent()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            var command = rule("RULE-OBS", "PI", "RULE-OBS-PI", "R");
            var created = data(b.post("/rules", command));
            String id = created.path("id").asString();
            command.put("revision", created.path("revision").asString());
            command.put("name", "name with spaces");
            command.put("prefix", "S");
            var changed = data(b.post("/rules/" + id + "/update", command)).path("rule");
            command.put("revision", changed.path("revision").asString());
            data(b.post("/rules/" + id + "/update", command));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_audit WHERE action<>'LOGIN'",
                                    Long.class))
                    .isEqualTo(3);
            jdbc.update("UPDATE sn_rule_version SET used=1 WHERE rule_id=?", id);
            command.put("name", "next name");
            command.put("prefix", "T");
            changed = data(b.post("/rules/" + id + "/update", command)).path("rule");
            var items = data(b.get("/audits/page?keyword=RULE-OBS&size=20")).path("items");
            assertThat(items.size()).isEqualTo(5);
            assertThat(items.get(0).path("action").asString()).isEqualTo("RULE_RENAME");
            assertThat(items.get(1).path("action").asString()).isEqualTo("RULE_VERSION");
            assertThat(items.get(2).path("action").asString()).isEqualTo("RULE_RENAME");
            assertThat(items.get(3).path("action").asString()).isEqualTo("sn:rule:update");
            assertThat(items.get(4).path("pi").asString()).isEqualTo("RULE-OBS-PI");
            assertThat(items.get(1).path("detail").asString())
                    .isEqualTo(
                            "RULE-OBS v2 prefix=T suffix= base=10 len=4 charset=0123456789 (was prefix=S suffix= base=10 len=4 charset=0123456789)");
            for (var item : items) assertThat(item.path("quantity").isNull()).isTrue();
            assertThat(audits(b, "action=RULE_RENAME")).hasSize(2);
            assertThat(audits(b, "action=RULE_VERSION")).hasSize(1);
            command.put("revision", changed.path("revision").asString());
            command.put("prefix", "Z");
            command.put("name", "must roll back");
            jdbc.execute(
                    "ALTER TABLE sn_audit ADD CONSTRAINT ck_rule_audit CHECK(action<>'RULE_RENAME' OR detail_text NOT LIKE '%must roll back%')");
            try {
                assertThat(b.post("/rules/" + id + "/update", command).statusCode()).isEqualTo(500);
            } finally {
                jdbc.execute("ALTER TABLE sn_audit DROP CHECK ck_rule_audit");
            }
            assertThat(jdbc.queryForObject("SELECT name FROM sn_rule WHERE id=?", String.class, id))
                    .isEqualTo("next name");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_audit WHERE result='SUCCESS' AND action<>'LOGIN'",
                                    Long.class))
                    .isEqualTo(5);
            var maintenance = new com.adtek.sn.mapper.MaintenanceMapper();
            jdbc.execute(
                    (org.springframework.jdbc.core.ConnectionCallback<Void>)
                            c -> {
                                var month = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
                                var original = maintenance.auditDigest(c, "sn_audit", month);
                                maintenance.copyAudit(c, month);
                                assertThat(maintenance.auditDigest(c, "sn_audit_archive", month))
                                        .isEqualTo(original);
                                return null;
                            });
            jdbc.update("DELETE FROM sn_audit");
            var archived =
                    data(b.get("/audits/page?keyword=RULE-OBS&storage=ARCHIVE&size=20"))
                            .path("items");
            assertThat(archived).isEqualTo(items);
            String csv = auditExport(b, "CSV", Map.of("keyword", "RULE-OBS"));
            drain();
            String content = new String(download(b, csv), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(content).contains("RULE-OBS name with spaces -> next name");
            assertThat(content).contains("prefix=T suffix= base=10 len=4 charset=0123456789");
        }
    }

    @Test
    void factoryFactsRetainNameNullQuantitySyncCountsAndOneFailurePerRequest() throws Exception {
        try (var b = new Browser("sn_hq")) {
            data(b.post("/factories", Map.of("code", "\u00a0MANUAL\u0085", "name", "手工厂")));
            var created = audits(b, "action=sn:factory:create").getFirst();
            assertThat(created.path("detail").asString()).isEqualTo("手工厂");
            assertThat(created.path("quantity").isNull()).isTrue();
            assertThat(created.path("factoryCode").asString()).isEqualTo("MANUAL");
            assertThat(b.post("/factories", Map.of("code", "", "name", "")).statusCode())
                    .isEqualTo(400);
            assertThat(b.post("/factories", Map.of("code", "MANUAL", "name", "重复")).statusCode())
                    .isEqualTo(409);
            assertThat(b.post("/factories", Map.of("code", "OTHER", "name", "手工厂")).statusCode())
                    .isEqualTo(409);
            assertThat(
                            b.post("/factories", Map.of("code", "SHORT", "name", "名".repeat(129)))
                                    .statusCode())
                    .isEqualTo(400);
            long beforeLong = count("sn_audit");
            assertThat(
                            b.post("/factories", Map.of("code", "X".repeat(65), "name", "过长编码"))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(count("sn_audit")).isEqualTo(beforeLong);
            var rejected = audits(b, "action=sn:factory:sync");
            assertThat(rejected).hasSize(4);
            assertThat(rejected.stream().map(r -> r.path("errorMessage").asString()))
                    .containsExactlyInAnyOrder(
                            "工厂编码与名称必填，编码不超过 64 位", "工厂编码或名称已存在：MANUAL/重复",
                            "工厂编码或名称已存在：OTHER/手工厂", "工厂编码与名称必填，编码不超过 64 位");
            for (var item : rejected) {
                assertThat(item.path("result").asString()).isEqualTo("FAILED");
                assertThat(item.path("detail").asString()).isEqualTo("POST /api/factories");
                assertThat(item.path("quantity").isNull()).isTrue();
            }
            jdbc.update(
                    "INSERT INTO sn_factory(code,name,source,enabled) VALUES('BLOCKED','冲突厂','MANUAL',1)");
            ERP.organizations =
                    List.of(
                            List.of("101", "MANUAL", "已改名", "A"),
                            List.of("102", "ERP-B", "乙工厂", "A"),
                            List.of("103", "CONFLICT", "冲突厂", "A"));
            data(b.post("/factories/sync", Map.of()));
            data(b.post("/factories/sync", Map.of()));
            var sync = audits(b, "action=sn:factory:sync");
            assertThat(sync.get(0).path("detail").asString())
                    .isEqualTo("inserted=0 updated=0 conflicts=[CONFLICT/冲突厂]");
            assertThat(sync.get(1).path("detail").asString())
                    .isEqualTo("inserted=1 updated=1 conflicts=[CONFLICT/冲突厂]");
            assertThat(sync.get(0).path("quantity").asString()).isEqualTo("3");
            ERP.organizations = List.of();
            data(b.post("/factories/sync", Map.of()));
            assertThat(audits(b, "action=sn:factory:sync").getFirst().path("quantity").asString())
                    .isEqualTo("0");
            String file = auditExport(b, "XLSX", Map.of("action", "sn:factory:create"));
            drain();
            try (var book =
                    org.apache.poi.ss.usermodel.WorkbookFactory.create(
                            new java.io.ByteArrayInputStream(download(b, file)))) {
                var sheet = book.getSheetAt(0);
                assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 24);
                assertThat(sheet.getRow(1).getCell(23).getStringCellValue()).isEqualTo("手工厂");
                assertThat(sheet.getRow(1).getCell(5).getStringCellValue())
                        .isEqualTo("FACTORY_CREATE");
                assertThat(
                                sheet.getRow(1)
                                        .getCell(
                                                14,
                                                org.apache.poi.ss.usermodel.Row.MissingCellPolicy
                                                        .RETURN_BLANK_AS_NULL))
                        .isNull();
            }
        }
    }

    @Test
    void factoryAuditFailureRollsBackSuccessButPreservesOriginalRejection() throws Exception {
        try (var b = new Browser("sn_hq")) {
            jdbc.execute(
                    "ALTER TABLE sn_audit ADD CONSTRAINT ck_factory_audit CHECK(action NOT LIKE 'sn:factory:%')");
            long revision =
                    jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class);
            try {
                assertThat(
                                b.post("/factories", Map.of("code", "ROLLBACK", "name", "回滚"))
                                        .statusCode())
                        .isEqualTo(500);
                var rejected = b.post("/factories", Map.of("code", "", "name", ""));
                assertThat(rejected.statusCode()).isEqualTo(400);
                assertThat(json.readTree(rejected.body()).path("code").asInt())
                        .isEqualTo(1004000033);
                assertThat(b.post("/factories/sync", Map.of()).statusCode()).isEqualTo(500);
            } finally {
                jdbc.execute("ALTER TABLE sn_audit DROP CHECK ck_factory_audit");
            }
            assertThat(count("sn_factory")).isZero();
            assertThat(count("sn_org_mapping")).isZero();
            assertThat(jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class))
                    .isEqualTo(revision);
            assertThat(audits(b, "action=sn:factory:sync")).isEmpty();
        }
    }

    @Test
    void fullOrderSyncRecordsFactoryCountsWhileBillOnlySyncDoesNot() throws Exception {
        try (var b = new Browser("sn_hq")) {
            sample();
            data(b.post("/orders/sync", Map.of()));
            var sync = audits(b, "action=sn:factory:sync");
            assertThat(sync).hasSize(1);
            assertThat(sync.getFirst().path("detail").asString())
                    .isEqualTo("inserted=2 updated=0 conflicts=[]");
            assertThat(sync.getFirst().path("quantity").asString()).isEqualTo("2");
            data(b.post("/orders/sync", Map.of("billNo", "MO-1")));
            assertThat(audits(b, "action=sn:factory:sync")).hasSize(1);
        }
    }

    final class Browser implements AutoCloseable {
        final HttpClient client;
        final String token, csrf;

        Browser(String username) throws Exception {
            client =
                    HttpClient.newBuilder()
                            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                            .build();
            var c = raw("GET", "/system/auth/csrf", null, null, null);
            csrf = json.readTree(c.body()).path("data").path("token").asString();
            var login =
                    raw(
                            "POST",
                            "/system/auth/login",
                            Map.of("username", username, "password", PASSWORD),
                            null,
                            csrf);
            assertThat(login.statusCode()).isEqualTo(200);
            token = json.readTree(login.body()).path("data").path("accessToken").asString();
        }

        HttpResponse<String> get(String path) throws Exception {
            return raw("GET", "/sn" + path, null, token, null);
        }

        HttpResponse<String> post(String path, Object body) throws Exception {
            return raw("POST", "/sn" + path, body, token, csrf);
        }

        HttpResponse<String> raw(
                String method, String path, Object body, String bearer, String csrfToken)
                throws Exception {
            if ("POST".equals(method) && csrfToken != null)
                csrfToken =
                        json.readTree(raw("GET", "/system/auth/csrf", null, null, null).body())
                                .path("data")
                                .path("token")
                                .asString();
            var builder =
                    HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                            .header("Content-Type", "application/json");
            if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
            if (csrfToken != null) builder.header("X-XSRF-TOKEN", csrfToken);
            builder.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
