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
                        "sys_task_progress",
                        "sys_file",
                        "sys_task",
                        "sys_operation",
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
        return data(b.post("/generation/previews", Map.of("billId", bill, "quantity", qty)))
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
                for (var row : wb.getSheetAt(0))
                    for (var cell : row)
                        assertThat(cell.getCellType())
                                .isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
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
            int old = snSettings.getMaxBatchQuantity();
            try {
                snSettings.setMaxBatchQuantity(1);
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
                snSettings.setMaxBatchQuantity(old);
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
