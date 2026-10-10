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

/** S6真实查询/固定双格式导出/当前权限与首页业务统计回归，手动驱动工作器注入故障；不连接真实ERP。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SnQueryIT {
    static final String SECRET = HexFormat.of().formatHex(new SecureRandom().generateSeed(32)),
            PASSWORD = "SnStage9!" + SECRET.substring(0, 24);
    static final K3ProtocolFixture ERP = new K3ProtocolFixture();

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_sn_stage6_query_it")
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
                        "sn_audit",
                        "sn_audit_archive",
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
                        "sn_generation_job",
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

    @Test
    void exactFiltersEmptyMaterialAndCursorReadEveryItemWithoutNumericLoss() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b), batch = acquire(b, factory);
            assertThat(items(b, "pi=PI-1")).hasSize(13);
            assertThat(
                            items(
                                    b,
                                    "factoryId="
                                            + factory
                                            + "&pi=PI-1&customer=C1&materialMode=EMPTY&status=TO_PRINT&billNo=MO-1&batchId="
                                            + batch))
                    .hasSize(2);
            assertThat(items(b, "sn=G0001")).hasSize(1);
            assertThat(items(b, "sn=G000")).isEmpty();
            assertThat(data(b.get("/items/count?pi=PI-1")).path("quantity").asString())
                    .isEqualTo("13");
            var row = items(b, "sn=G0001").getFirst();
            assertThat(row.path("factoryId").asString()).isEqualTo(factory);
            assertThat(row.path("seqDec").asString()).isEqualTo("1");
            assertThat(row.path("seqText").asString()).isEqualTo("0001");
            assertThat(row.path("sourceBillId").asString()).isEqualTo("9007199254740993");
            assertThat(row.path("createdAt").asString()).endsWith("Z");
            String cursor = data(b.get("/items?size=1&pi=PI-1")).path("nextCursor").asString();
            assertThat(b.get("/items?pi=PI-2&cursor=" + cursor).statusCode()).isEqualTo(400);
            assertThat(b.get("/items?pi=PI-1&cursor=" + cursor + "x").statusCode()).isEqualTo(400);
            assertThat(b.get("/items?materialMode=VALUE").statusCode()).isEqualTo(400);
        }
    }

    @Test
    void factoryAndCrossPiReadsExcludeUnassignedAndUnownedPis() throws Exception {
        try (var hq = new Browser("sn_hq")) {
            String source = allocated(hq), other = target(source);
            jdbc.update(
                    "UPDATE sn_item SET factory_id=?,status='TO_ACQUIRE' WHERE sn='G0001'", other);
            jdbc.update(
                    "UPDATE sn_item SET factory_id=NULL,status='PENDING_ALLOC' WHERE sn='G0002'");
            user(11, "local_reader", 5);
            jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)", source);
            try (var local = new Browser("local_reader")) {
                assertThat(items(local, "pi=PI-1")).hasSize(11);
                assertThat(items(local, "pi=PI-1&piAll=true")).hasSize(12);
                assertThat(items(local, "status=PENDING_ALLOC")).isEmpty();
                for (String endpoint : List.of("/items", "/items/page")) {
                    var denied = local.get(endpoint + "?pi=UNOWNED&piAll=true");
                    assertThat(denied.statusCode()).isEqualTo(403);
                    var body = json.readTree(denied.body());
                    assertThat(body.path("code").asInt()).isEqualTo(1003000004);
                    assertThat(body.path("params").path("reason").asString())
                            .isEqualTo("QUERY_PI_NOT_OWNED");
                    assertThat(body.path("params").path("pi").asString()).isEqualTo("UNOWNED");
                }
                assertThat(local.get("/items?factoryId=" + other).statusCode()).isEqualTo(403);
                String cursor = data(hq.get("/items?size=1")).path("nextCursor").asString();
                assertThat(local.get("/items?cursor=" + cursor).statusCode()).isEqualTo(400);
            }
            assertThat(items(hq, "status=PENDING_ALLOC")).hasSize(1);
            assertThat(items(hq, "pi=PI-1&piAll=true")).hasSize(12);
        }
    }

    @Test
    void bothFormatsUseFixedSnapshotsAndLostFilesRecoverSameBytes() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String source = allocated(b);
            String csv = export(b, "CSV", Map.of("pi", "PI-1")),
                    xlsx = export(b, "XLSX", Map.of("pi", "PI-1"));
            jdbc.update(
                    "UPDATE sn_item SET"
                            + " pi='CHANGED',customer='AFTER',material='AFTER',factory_id=?,status='PRINTED'",
                    target(source));
            jdbc.update("UPDATE sn_factory SET name=CONCAT('改名-',id),code=CONCAT('X-',code)");
            drain();
            byte[] csvBytes = download(b, csv), xlsxBytes = download(b, xlsx);
            assertThat(new String(csvBytes, java.nio.charset.StandardCharsets.UTF_8))
                    .contains("G0001", "G0013", "PI-1", "TO_ACQUIRE")
                    .doesNotContain("CHANGED", "AFTER");
            try (var workbook =
                    org.apache.poi.ss.usermodel.WorkbookFactory.create(
                            new java.io.ByteArrayInputStream(xlsxBytes))) {
                assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(13);
                var sheet = workbook.getSheetAt(0);
                assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 11);
                assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("完整SN");
                for (var row : sheet)
                    for (var cell : row) {
                        boolean numeric =
                                row.getRowNum() > 0
                                        && (cell.getColumnIndex() == 0
                                                || cell.getColumnIndex() == 3);
                        assertThat(cell.getCellType())
                                .isEqualTo(
                                        numeric
                                                ? org.apache.poi.ss.usermodel.CellType.NUMERIC
                                                : org.apache.poi.ss.usermodel.CellType.STRING);
                    }
            }
            for (String id : List.of(csv, xlsx)) {
                String ext = id.equals(csv) ? "csv" : "xlsx";
                var root =
                        java.nio.file.Path.of(taskSettings.fileRoot()).toAbsolutePath().normalize();
                var file = root.resolve("objects").resolve(id + "." + ext).normalize();
                assertThat(file.startsWith(root)).isTrue();
                java.nio.file.Files.delete(file);
                assertThat(b.raw("GET", "/files/" + id, null, b.token, null).statusCode())
                        .isEqualTo(409);
                data(b.raw("POST", "/tasks/" + id + "/recover", Map.of(), b.token, b.csrf));
                drain();
                assertThat(download(b, id)).isEqualTo(id.equals(csv) ? csvBytes : xlsxBytes);
            }
            assertThat(count("sn_query_export")).isEqualTo(2);
            assertThat(count("sn_query_export_row")).isEqualTo(26);
            assertThat(data(b.get("/exports")).path("total").asString()).isEqualTo("2");
        }
    }

    @Test
    void currentDownloadAuthorizationChecksOriginalScopeAndNeverFollowsMovedSn() throws Exception {
        try (var hq = new Browser("sn_hq")) {
            String source = allocated(hq), other = target(source);
            user(11, "local", 5);
            jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)", source);
            String id;
            try (var local = new Browser("local")) {
                id = export(local, "CSV", Map.of("pi", "PI-1"));
                drain();
                assertThat(download(local, id)).isNotEmpty();
                assertThat(hq.raw("GET", "/files/" + id, null, hq.token, null).statusCode())
                        .isEqualTo(404);
            }
            jdbc.update("UPDATE sn_item SET factory_id=?", other);
            jdbc.update("DELETE FROM sn_user_factory WHERE user_id=11");
            jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)", other);
            jdbc.update("UPDATE sys_user SET security_version=security_version+1 WHERE id=11");
            try (var changed = new Browser("local")) {
                assertThat(items(changed, "pi=PI-1")).hasSize(13);
                assertThat(
                                changed.raw("GET", "/files/" + id, null, changed.token, null)
                                        .statusCode())
                        .isEqualTo(403);
            }
        }
    }

    @Test
    void allFactoryReadAndLocalExportAreIntersectedPerAction() throws Exception {
        try (var hq = new Browser("sn_hq")) {
            String source = allocated(hq), other = target(source);
            jdbc.update("UPDATE sn_item SET factory_id=? WHERE sn='G0001'", other);
            jdbc.update(
                    "INSERT INTO sys_role(id,role_code,role_name,data_scope,factory_scope)"
                            + " VALUES(1000,'QUERY_GLOBAL','全厂读',1,2),(1001,'EXPORT_LOCAL','局部导出',1,1)");
            jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1000,280),(1001,281)");
            user(11, "mixed", 1000);
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(11,1001)");
            jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)", source);
            try (var mixed = new Browser("mixed")) {
                assertThat(items(mixed, "pi=PI-1")).hasSize(13);
                String id = export(mixed, "CSV", Map.of("pi", "PI-1"));
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT quantity FROM sn_query_export WHERE task_id=?",
                                        Long.class,
                                        id))
                        .isEqualTo(12);
                drain();
                assertThat(new String(download(mixed, id), java.nio.charset.StandardCharsets.UTF_8))
                        .doesNotContain("G0001");
            }
        }
    }

    @Test
    void dashboardUsesSuccessfulAuditQuantitiesAndNoGlobalJobsLeakIntoFactoryRole()
            throws Exception {
        try (var hq = new Browser("sn_hq")) {
            String source = allocated(hq), other = target(source);
            acquire(hq, source);
            String print =
                    data(hq.post("/prints", take(uuid(), source, "PI-1")))
                            .path("taskId")
                            .asString();
            drain();
            jdbc.update(
                    "UPDATE sn_item SET"
                            + " status='TO_ACQUIRE',factory_id=?,current_batch_id=NULL,current_print_id=NULL",
                    other);
            var all = data(hq.get("/dashboard"));
            assertThat(all.path("totalGenerated").asString()).isEqualTo("13");
            assertThat(all.path("recentJobs").size()).isEqualTo(1);
            var job = all.path("jobs").get(0);
            assertThat(job.path("billNo").asString()).isEqualTo("MO-1");
            assertThat(job.path("quantity").asString()).isEqualTo("13");
            assertThat(job.path("processed").asString()).isEqualTo("13");
            assertThat(data(hq.get("/context")).path("role").asString()).isEqualTo("admin");
            Map<String, String> today = new HashMap<>();
            all.path("today")
                    .forEach(
                            n ->
                                    today.put(
                                            n.path("name").asString(),
                                            n.path("quantity").asString()));
            assertThat(today)
                    .containsEntry("GENERATE", "13")
                    .containsEntry("TAKE", "13")
                    .containsEntry("PRINT", "13");
            // 今日动作来自不可变审计；业务任务清理或导入不能抹掉当天已发生的生成事实。
            jdbc.update("DELETE FROM sn_generation_job");
            jdbc.update("DELETE FROM sn_work WHERE kind='GENERATE'");
            var retained = data(hq.get("/dashboard"));
            assertThat(retained.path("jobs").size()).isZero();
            assertThat(retained.path("today")).isEqualTo(all.path("today"));
            assertThat(all.path("zone").asString()).isEqualTo("Asia/Shanghai");
            user(11, "factory_reader", 5);
            jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)", other);
            try (var local = new Browser("factory_reader")) {
                var dashboard = data(local.get("/dashboard"));
                assertThat(dashboard.path("totalGenerated").isNull()).isTrue();
                assertThat(dashboard.path("pendingTransfers").asString()).isEqualTo("0");
                assertThat(dashboard.path("recentJobs").size()).isZero();
                assertThat(dashboard.path("jobs").size()).isZero();
                var context = data(local.get("/context"));
                assertThat(context.path("role").asString()).isEqualTo("query");
                assertThat(context.path("allFactories").asBoolean()).isFalse();
                assertThat(context.path("factories").size()).isEqualTo(1);
                assertThat(context.path("factories").get(0).path("id").asString()).isEqualTo(other);
                assertThat(local.get("/transfers").statusCode()).isEqualTo(200);
                assertThat(
                                local.post(
                                                "/transfers",
                                                Map.of(
                                                        "operationId",
                                                        uuid(),
                                                        "factoryId",
                                                        other,
                                                        "pi",
                                                        "PI-1",
                                                        "scope",
                                                        "PI",
                                                        "toFactoryId",
                                                        source,
                                                        "toPi",
                                                        "PI-2",
                                                        "targetSource",
                                                        "MANUAL",
                                                        "reason",
                                                        "read-only role check"))
                                        .statusCode())
                        .isEqualTo(403);
                assertThat(dashboard.path("today").size()).isZero();
                assertThat(dashboard.path("states").get(0).path("quantity").asString())
                        .isEqualTo("13");
            }
        }
    }

    @Test
    void dashboardJobsAreSharedOnlyWithCurrentHeadquartersGenerationPermission() throws Exception {
        try (var hq = new Browser("sn_hq")) {
            allocated(hq);
            user(11, "another_hq", 2);
            user(12, "global_reader", 4);
            try (var colleague = new Browser("another_hq");
                    var reader = new Browser("global_reader")) {
                var dashboard = data(colleague.get("/dashboard"));
                assertThat(dashboard.path("jobs").size()).isEqualTo(1);
                assertThat(dashboard.path("jobs").get(0).path("billNo").asString())
                        .isEqualTo("MO-1");
                var job = dashboard.path("jobs").get(0);
                assertThat(job.path("id").asString()).matches("[1-9][0-9]*");
                String taskId = job.path("taskId").asString();
                // 重试中的旧代次进度不能展示为本轮进度；订单号保留提交时快照。
                jdbc.update(
                        "UPDATE sn_order_bill SET bill_no='CHANGED-AFTER-SUBMIT' WHERE bill_no='MO-1'");
                jdbc.update("UPDATE sn_work SET state='PENDING' WHERE task_id=?", taskId);
                jdbc.update(
                        "UPDATE sys_task SET execution_epoch=execution_epoch+1 WHERE id=?", taskId);
                var retry = data(colleague.get("/dashboard")).path("jobs").get(0);
                assertThat(retry.path("processed").asString()).isEqualTo("0");
                assertThat(retry.path("billNo").asString()).isEqualTo("MO-1");
                assertThat(retry.path("id").asString()).isEqualTo(job.path("id").asString());
                var context = data(reader.get("/context"));
                assertThat(context.path("role").asString()).isEqualTo("query");
                assertThat(context.path("allFactories").asBoolean()).isTrue();
                assertThat(context.path("factories").size()).isZero();
                assertThat(data(reader.get("/dashboard")).path("jobs").size()).isZero();
                assertThat(reader.get("/transfers").statusCode()).isEqualTo(200);
            }
        }
    }

    @Autowired com.adtek.sn.service.support.SnWorkSettings snSettings;

    @Test
    void oversizedSnapshotAndAuditFailureLeaveNoPartialExport() throws Exception {
        try (var b = new Browser("sn_hq")) {
            allocated(b);
            int old = snSettings.getMaxBatchQuantity();
            try {
                snSettings.setMaxBatchQuantity(2);
                assertThat(
                                b.post(
                                                "/exports",
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
            assertThat(count("sn_query_export")).isZero();
            assertThat(count("sn_query_export_row")).isZero();
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_s6_export_audit"
                            + " CHECK(action<>'sn:query:export:submit' OR success=FALSE)");
            try {
                assertThat(
                                b.post(
                                                "/exports",
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
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_s6_export_audit");
            }
            assertThat(count("sn_query_export")).isZero();
            assertThat(count("sys_file")).isZero();
        }
    }

    @Test
    void publishedBeforeReadyFailureRetainsSnapshotAndCanRecoverWithoutNewOperation()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            allocated(b);
            String id = export(b, "CSV", Map.of());
            jdbc.execute("ALTER TABLE sys_file ADD CONSTRAINT ck_s6_ready CHECK(state<>'READY')");
            try {
                fail(id);
            } finally {
                jdbc.execute("ALTER TABLE sys_file DROP CHECK ck_s6_ready");
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM sys_operation WHERE task_id=?",
                                    String.class,
                                    id))
                    .isEqualTo("SUCCEEDED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM sys_file WHERE task_id=?", String.class, id))
                    .isEqualTo("FAILED");
            jdbc.update("UPDATE sn_item SET sn=CONCAT('changed',sn)");
            data(b.raw("POST", "/tasks/" + id + "/recover", Map.of(), b.token, b.csrf));
            drain();
            assertThat(new String(download(b, id), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("G0001")
                    .doesNotContain("changed");
            assertThat(count("sn_query_export")).isEqualTo(1);
        }
    }

    @Test
    void pageUsesCreationOrderPiSequenceBusinessBatchAndCurrentScope() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b), batch = acquire(b, factory);
            String number =
                    jdbc.queryForObject(
                            "SELECT batch_no FROM sn_acquire_batch WHERE id=?",
                            String.class,
                            batch);
            var first = data(b.get("/items/page?size=5"));
            assertThat(first.path("total").asString()).isEqualTo("13");
            assertThat(first.path("totalCapped").asBoolean()).isFalse();
            assertThat(first.path("items").get(0).path("sn").asString()).isEqualTo("G0013");
            assertThat(first.path("items").get(4).path("sn").asString()).isEqualTo("G0009");
            var last = data(b.get("/items/page?size=5&page=3"));
            assertThat(last.path("items").size()).isEqualTo(3);
            assertThat(last.path("items").get(2).path("sn").asString()).isEqualTo("G0001");
            var ordered = data(b.get("/items/page?size=20&pi=PI-1&batchNo=" + number));
            assertThat(ordered.path("items").size()).isEqualTo(13);
            assertThat(ordered.path("items").get(0).path("sn").asString()).isEqualTo("G0012");
            assertThat(ordered.path("items").get(2).path("sn").asString()).isEqualTo("G0001");
            assertThat(ordered.path("items").get(0).path("currentBatchNo").asString())
                    .isEqualTo(number);
            assertThat(data(b.get("/items/page?batchNo=UNKNOWN")).path("items").size()).isZero();
            assertThat(b.get("/items/page?batchId=" + number).statusCode()).isEqualTo(400);
            assertThat(b.get("/items/page?page=0").statusCode()).isEqualTo(400);
            String file = export(b, "CSV", Map.of("batchNo", number));
            drain();
            String csv = new String(download(b, file), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(csv).contains("序号,完整SN,进制流水", number);
            assertThat(csv.indexOf("G0001")).isLessThan(csv.indexOf("G0002"));
            assertThat(csv.indexOf("G0002")).isLessThan(csv.indexOf("G0012"));
            user(11, "different_factory", 5);
            jdbc.update(
                    "INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)",
                    target(factory));
            try (var reader = new Browser("different_factory")) {
                assertThat(data(reader.get("/items/page?batchNo=" + number)).path("items").size())
                        .isZero();
                assertThat(reader.get("/items/page?factoryId=" + factory).statusCode())
                        .isEqualTo(403);
                assertThat(reader.get("/items/page?pi=PI-1&piAll=true").statusCode())
                        .isEqualTo(403);
            }
        }
    }

    @Test
    void legacyQueryFormatStillRecoversOriginalTwentyTwoColumnBytes() throws Exception {
        try (var b = new Browser("sn_hq")) {
            allocated(b);
            var original = items(b, "");
            for (String format : List.of("CSV", "XLSX")) {
                String id = export(b, format, Map.of());
                jdbc.update("UPDATE sn_query_export SET format_version=1 WHERE task_id=?", id);
                for (int i = 0; i < original.size(); i++) {
                    var row =
                            json.treeToValue(original.get(i), com.adtek.sn.vo.SnQueryItemVO.class);
                    jdbc.update(
                            "UPDATE sn_query_export_row SET cells=? WHERE task_id=? AND ordinal=?",
                            json.writeValueAsString(
                                    com.adtek.sn.service.support.SnQueryFiles.cells(i + 1L, row)),
                            id,
                            i + 1);
                }
                drain();
                byte[] before = download(b, id);
                if (format.equals("CSV"))
                    assertThat(new String(before, java.nio.charset.StandardCharsets.UTF_8))
                            .startsWith("\uFEFF\"ordinal\",\"sn\"")
                            .contains("\"source_entry_id\"");
                else
                    try (var workbook =
                            org.apache.poi.ss.usermodel.WorkbookFactory.create(
                                    new java.io.ByteArrayInputStream(before))) {
                        assertThat(workbook.getSheetAt(0).getRow(0).getLastCellNum())
                                .isEqualTo((short) 22);
                        assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getCellType())
                                .isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
                    }
                var directory =
                        java.nio.file.Path.of(taskSettings.fileRoot()).toAbsolutePath().normalize();
                var path =
                        directory
                                .resolve("objects")
                                .resolve(id + "." + format.toLowerCase(Locale.ROOT))
                                .normalize();
                assertThat(path.startsWith(directory)).isTrue();
                java.nio.file.Files.delete(path);
                assertThat(b.raw("GET", "/files/" + id, null, b.token, null).statusCode())
                        .isEqualTo(409);
                data(b.raw("POST", "/tasks/" + id + "/recover", Map.of(), b.token, b.csrf));
                drain();
                assertThat(download(b, id)).isEqualTo(before);
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
