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

/** S5真实HTTP/MySQL/Redis领取、固定历史、打印文件及服务主体回归，手动驱动工作器注入故障；不连接真实ERP。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SnAcquireIT {
    static final String SECRET = HexFormat.of().formatHex(new SecureRandom().generateSeed(32)),
            PASSWORD = "SnStage9!" + SECRET.substring(0, 24);
    static final K3ProtocolFixture ERP = new K3ProtocolFixture();

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_sn_stage5_it")
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

    @Test
    void mixedQualificationDoesNotBorrowSiblingOrderAndPersistsEveryOutcome() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b);
            jdbc.update("UPDATE sn_order_entry SET active=FALSE WHERE source_entry_id='12'");
            String task = acquire(b, factory);
            var result = data(b.get("/acquisitions/results/" + task));
            assertThat(result.path("eligible").asString()).isEqualTo("10");
            assertThat(result.path("blocked").asString()).isEqualTo("3");
            assertThat(count("sn_acquire_member")).isEqualTo(10);
            assertThat(count("sn_acquire_result")).isEqualTo(13);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE'",
                                    Long.class))
                    .isEqualTo(3);
            assertThat(
                            data(b.get("/acquisitions/segments?materialMode=EMPTY"))
                                    .path("total")
                                    .asString())
                    .isEqualTo("1");
            assertThat(
                            data(b.get("/acquisitions/pis"))
                                    .path("items")
                                    .get(0)
                                    .path("toPrint")
                                    .asString())
                    .isEqualTo("10");
        }
    }

    @Test
    void zeroEligibleReplayNeverTakesNewlyEligibleMembers() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b);
            jdbc.update("UPDATE sn_order_entry SET active=FALSE");
            String operation = uuid();
            var command = take(operation, factory, "PI-1");
            String task = data(b.post("/acquisitions", command)).path("taskId").asString();
            run(task);
            assertThat(count("sn_acquire_batch")).isZero();
            assertThat(data(b.get("/acquisitions/results/" + task)).path("eligible").asString())
                    .isEqualTo("0");
            jdbc.update("UPDATE sn_order_entry SET active=TRUE");
            assertThat(data(b.post("/acquisitions", command)).path("taskId").asString())
                    .isEqualTo(task);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE'",
                                    Long.class))
                    .isEqualTo(13);
            acquire(b, factory);
            assertThat(count("sn_acquire_batch")).isEqualTo(1);
        }
    }

    @Test
    void fixedBatchReadsAllOrdinalsAndDoesNotFollowTransfersOrRenames() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b), batch = acquire(b, factory);
            var before = members(b, batch);
            assertThat(before).hasSize(13);
            String immutable = before.get(0).path("snapshot").toString();
            jdbc.update("UPDATE sn_factory SET name='改名' WHERE id=?", factory);
            jdbc.update(
                    "UPDATE sn_item SET pi='TRANSFERRED',factory_id=(SELECT id FROM sn_factory"
                            + " WHERE id<>? LIMIT 1),current_batch_id=NULL,status='TO_ACQUIRE'",
                    factory);
            var after = members(b, batch);
            assertThat(after).hasSize(13);
            assertThat(after.get(0).path("snapshot").toString()).isEqualTo(immutable);
            assertThat(after.get(0).path("currentMember").asBoolean()).isFalse();
            String cursor =
                    data(b.get("/acquire-batches/" + batch + "/items?size=1"))
                            .path("nextCursor")
                            .asString();
            assertThat(
                            b.get("/acquire-batches/" + batch + "/items?cursor=" + cursor + "x")
                                    .statusCode())
                    .isEqualTo(400);
        }
    }

    @Test
    void callbackProtectsOriginalBatchAndReturnsCompleteDeduplicatedClassification()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b), batch = acquire(b, factory);
            jdbc.update("UPDATE sn_item SET status='APPLYING' WHERE sn='G0002'");
            jdbc.update("UPDATE sn_item SET pi='ELSEWHERE' WHERE sn='G0003'");
            jdbc.update("UPDATE sn_item SET current_batch_id=? WHERE sn='G0004'", uuid());
            jdbc.update("UPDATE sn_item SET status='PRINTED' WHERE sn='G0005'");
            jdbc.update(
                    "UPDATE sn_item SET status='PRINTED',current_batch_id=? WHERE sn='G0006'",
                    uuid());
            var command =
                    Map.of(
                            "operationId",
                            uuid(),
                            "batchId",
                            batch,
                            "targetStatus",
                            "PRINTED",
                            "sns",
                            List.of(
                                    "G0001", "G0001", "G0002", "G0003", "G0004", "G0005", "G0006",
                                    "OUTSIDE"));
            // 人员不允许进入MES路径；直接共享用例通过真实认证上下文由测试专用系统服务主体调用在独立测试验证。

            String task = callbackAsService(b, factory, command);
            run(task);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT outcome FROM sn_acquire_result WHERE task_id=? ORDER BY"
                                            + " sn",
                                    String.class,
                                    task))
                    .containsExactly(
                            "UPDATED",
                            "APPLYING",
                            "TRANSFERRED",
                            "BATCH_CHANGED",
                            "ALREADY_PRINTED",
                            "BATCH_CHANGED",
                            "NOT_IN_BATCH");
        }
    }

    String callbackAsService(Browser b, String factory, Object command) throws Exception {
        var client =
                data(
                        b.raw(
                                "POST",
                                "/system/service-clients",
                                Map.of(
                                        "displayName",
                                        "MES夹具",
                                        "factoryIds",
                                        List.of(factory),
                                        "permissions",
                                        List.of(
                                                "sn:acquire:view",
                                                "sn:acquire:take",
                                                "sn:acquire:callback",
                                                "sn:items:read")),
                                b.token,
                                b.csrf));
        var token =
                data(b.raw(
                                "POST",
                                "/integrations/auth/token",
                                Map.of(
                                        "clientId",
                                        client.path("client").path("clientId").asString(),
                                        "clientSecret",
                                        client.path("clientSecret").asString()),
                                null,
                                null))
                        .path("accessToken")
                        .asString();
        return data(b.raw("POST", "/integrations/sn/print-confirmations", command, token, null))
                .path("taskId")
                .asString();
    }

    @Test
    void printConfirmationAndBothFilesStayFixedAfterCurrentRowsChange() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b);
            acquire(b, factory);
            var command = take(uuid(), factory, "PI-1");
            var result = data(b.post("/prints", command));
            String print = result.path("taskId").asString();
            assertThat(result.path("operationState").asString()).isEqualTo("SUCCEEDED");
            assertThat(result.path("fileState").asString()).isEqualTo("PREPARING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='PRINTED'",
                                    Long.class))
                    .isEqualTo(13);
            assertThat(
                            b.raw("POST", "/tasks/" + print + "/cancel", Map.of(), b.token, b.csrf)
                                    .statusCode())
                    .isEqualTo(409);
            drain();
            String csv =
                    jdbc.queryForObject(
                            "SELECT csv_task_id FROM sn_print WHERE id=?", String.class, print);
            byte[] xlsxBefore = download(b, print), csvBefore = download(b, csv);
            assertThat(new String(csvBefore, java.nio.charset.StandardCharsets.UTF_8))
                    .contains("G0001", "G0013");
            try (var workbook =
                    org.apache.poi.ss.usermodel.WorkbookFactory.create(
                            new java.io.ByteArrayInputStream(xlsxBefore))) {
                assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(13);
                assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getCellType())
                        .isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            }
            jdbc.update(
                    "UPDATE sn_item SET"
                            + " pi='TRANSFERRED',current_batch_id=NULL,current_print_id=NULL,status='TO_ACQUIRE'");
            assertThat(download(b, print)).isEqualTo(xlsxBefore);
            assertThat(data(b.post("/prints", command)).path("taskId").asString()).isEqualTo(print);
            java.nio.file.Path root =
                    java.nio.file.Path.of(taskSettings.fileRoot()).toAbsolutePath().normalize();
            var file = root.resolve("objects").resolve(print + ".xlsx").normalize();
            assertThat(file.startsWith(root)).isTrue();
            java.nio.file.Files.delete(file);
            assertThat(b.raw("GET", "/files/" + print, null, b.token, null).statusCode())
                    .isEqualTo(409);
            data(b.raw("POST", "/tasks/" + print + "/recover", Map.of(), b.token, b.csrf));
            drain();
            assertThat(download(b, print)).isEqualTo(xlsxBefore);
            assertThat(count("sn_print")).isEqualTo(1);
        }
    }

    @Test
    void serviceCredentialsCannotLoginToUiAndRotationKeepsStableSubject() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b);
            var response =
                    data(
                            b.raw(
                                    "POST",
                                    "/system/service-clients",
                                    Map.of(
                                            "displayName",
                                            "MES测试",
                                            "factoryIds",
                                            List.of(factory),
                                            "permissions",
                                            List.of(
                                                    "sn:acquire:view",
                                                    "sn:acquire:take",
                                                    "sn:items:read")),
                                    b.token,
                                    b.csrf));
            String client = response.path("client").path("clientId").asString(),
                    secret = response.path("clientSecret").asString(),
                    id = response.path("client").path("id").asString();
            String token =
                    data(b.raw(
                                    "POST",
                                    "/integrations/auth/token",
                                    Map.of("clientId", client, "clientSecret", secret),
                                    null,
                                    null))
                            .path("accessToken")
                            .asString();
            assertThat(b.raw("GET", "/system/auth/me", null, token, null).statusCode())
                    .isEqualTo(403);
            assertThat(
                            b.raw(
                                            "POST",
                                            "/system/auth/login",
                                            Map.of("username", client, "password", secret),
                                            null,
                                            b.csrf)
                                    .statusCode())
                    .isEqualTo(401);
            assertThat(
                            b.raw(
                                            "GET",
                                            "/integrations/sn/items?factoryId="
                                                    + factory
                                                    + "&pi=PI-1",
                                            null,
                                            b.token,
                                            null)
                                    .statusCode())
                    .isEqualTo(403);
            String operation = uuid();
            var command = take(operation, factory, "PI-1");
            String task =
                    data(b.raw("POST", "/integrations/sn/acquisitions", command, token, null))
                            .path("taskId")
                            .asString();
            run(task);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT principal_type FROM sys_operation WHERE task_id=?",
                                    String.class,
                                    task))
                    .isEqualTo("SERVICE");
            var rotated =
                    data(
                            b.raw(
                                    "POST",
                                    "/system/service-clients/" + id + "/rotate",
                                    Map.of("version", "1"),
                                    b.token,
                                    b.csrf));
            assertThat(rotated.path("client").path("id").asString()).isEqualTo(id);
            assertThat(b.raw("GET", "/tasks/" + task, null, token, null).statusCode())
                    .isEqualTo(401);
            String next =
                    data(b.raw(
                                    "POST",
                                    "/integrations/auth/token",
                                    Map.of(
                                            "clientId",
                                            client,
                                            "clientSecret",
                                            rotated.path("clientSecret").asString()),
                                    null,
                                    null))
                            .path("accessToken")
                            .asString();
            assertThat(
                            data(b.raw(
                                            "POST",
                                            "/integrations/sn/acquisitions",
                                            command,
                                            next,
                                            null))
                                    .path("taskId")
                                    .asString())
                    .isEqualTo(task);
            assertThat(count("sn_acquire_batch")).isEqualTo(1);
        }
    }

    record Mes(String id, String client, String secret, String token) {}

    Mes mes(Browser b, String factory, List<String> permissions) throws Exception {
        var credential =
                data(
                        b.raw(
                                "POST",
                                "/system/service-clients",
                                Map.of(
                                        "displayName",
                                        "MES范围夹具",
                                        "factoryIds",
                                        List.of(factory),
                                        "permissions",
                                        permissions),
                                b.token,
                                b.csrf));
        var c = credential.path("client");
        String secret = credential.path("clientSecret").asString();
        return new Mes(
                c.path("id").asString(),
                c.path("clientId").asString(),
                secret,
                data(b.raw(
                                "POST",
                                "/integrations/auth/token",
                                Map.of(
                                        "clientId",
                                        c.path("clientId").asString(),
                                        "clientSecret",
                                        secret),
                                null,
                                null))
                        .path("accessToken")
                        .asString());
    }

    @Test
    void sameIntentAndCompetingIntentHaveOneBatchAndNoOrphanOperation() throws Exception {
        try (var b = new Browser("sn_hq");
                var pool = Executors.newFixedThreadPool(4)) {
            String factory = allocated(b);
            var m = mes(b, factory, List.of("sn:acquire:take", "sn:acquire:view"));
            var command = take(uuid(), factory, "PI-1");
            List<Future<String>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                pending.add(
                        pool.submit(
                                () ->
                                        data(b.raw(
                                                        "POST",
                                                        "/integrations/sn/acquisitions",
                                                        command,
                                                        m.token(),
                                                        null))
                                                .path("taskId")
                                                .asString()));
            Set<String> ids = new HashSet<>();
            for (var f : pending) ids.add(f.get(30, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(count("sn_acquire_work")).isEqualTo(1);
            assertThat(
                            b.raw(
                                            "POST",
                                            "/integrations/sn/acquisitions",
                                            take(uuid(), factory, "PI-1"),
                                            m.token(),
                                            null)
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(count("sn_acquire_work")).isEqualTo(1);
            String id = ids.iterator().next();
            run(id);
            assertThat(count("sn_acquire_batch")).isEqualTo(1);
            var conflict =
                    b.raw(
                            "POST",
                            "/integrations/sn/acquisitions",
                            take(command.get("operationId").toString(), factory, "PI-2"),
                            m.token(),
                            null);
            assertThat(conflict.statusCode()).isEqualTo(409);
            var error = json.readTree(conflict.body());
            assertThat(error.path("code").asInt()).isEqualTo(1003000007);
            assertThat(error.path("params").path("factoryId").asString()).isEqualTo(factory);
            assertThat(error.path("params").path("pi").asString()).isEqualTo("PI-2");
            assertThat(error.path("params").path("operationId").asString())
                    .isEqualTo(command.get("operationId").toString());
        }
    }

    @Test
    void takeAndPrintSuccessAuditFailureRollBackEveryBusinessWrite() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b);
            String id =
                    data(b.post("/acquisitions", take(uuid(), factory, "PI-1")))
                            .path("taskId")
                            .asString();
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_s5_audit"
                            + " CHECK(action<>'sn:acquire:take' OR success=FALSE)");
            try {
                fail(id);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_s5_audit");
            }
            assertThat(count("sn_acquire_batch")).isZero();
            assertThat(count("sn_acquire_member")).isZero();
            assertThat(count("sn_acquire_result")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE' AND"
                                            + " current_batch_id IS NULL",
                                    Long.class))
                    .isEqualTo(13);
            acquire(b, factory);
            long operations = count("sys_operation");
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_s5_print"
                            + " CHECK(action<>'sn:print:execute' OR success=FALSE)");
            try {
                assertThat(b.post("/prints", take(uuid(), factory, "PI-1")).statusCode())
                        .isGreaterThanOrEqualTo(500);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_s5_print");
            }
            assertThat(count("sn_print")).isZero();
            assertThat(count("sn_print_member")).isZero();
            assertThat(count("sys_file")).isZero();
            assertThat(count("sys_operation")).isEqualTo(operations);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='TO_PRINT'",
                                    Long.class))
                    .isEqualTo(13);
        }
    }

    @Test
    void transferExceptionOnlyAdmitsItsExactMemberAtItsProvenDestination() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b);
            jdbc.update("UPDATE sn_order_entry SET active=FALSE");
            String proof = uuid();
            jdbc.update(
                    "INSERT INTO"
                            + " sn_acquire_exception(id,gen_month,item_id,factory_id,pi,transfer_id)"
                            + " SELECT ?,gen_month,id,factory_id,pi,? FROM sn_item WHERE sn='G0001'",
                    proof,
                    uuid());
            jdbc.update(
                    "UPDATE sn_item SET acquire_exception_id=? WHERE sn IN ('G0001','G0002')",
                    proof);
            String task = acquire(b, factory);
            var result = data(b.get("/acquisitions/results/" + task));
            assertThat(result.path("eligible").asString()).isEqualTo("1");
            assertThat(result.path("blocked").asString()).isEqualTo("12");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT sn FROM sn_item WHERE status='TO_PRINT'", String.class))
                    .containsExactly("G0001");
        }
    }

    @Test
    void callbackRangeRequiresFixedContinuityAndReplayKeepsOriginalClassification()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b), batch = acquire(b, factory);
            var m =
                    mes(
                            b,
                            factory,
                            List.of("sn:acquire:view", "sn:acquire:callback", "sn:items:read"));
            String operation = uuid();
            var command =
                    Map.of(
                            "operationId",
                            operation,
                            "batchId",
                            batch,
                            "targetStatus",
                            "PRINTED",
                            "ranges",
                            List.of(
                                    Map.of("startSn", "G0004", "endSn", "G0001"),
                                    Map.of("startSn", "G0002", "endSn", "G0003")));
            String task =
                    data(b.raw(
                                    "POST",
                                    "/integrations/sn/print-confirmations",
                                    command,
                                    m.token(),
                                    null))
                            .path("taskId")
                            .asString();
            run(task);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT updated_qty FROM sn_acquire_work WHERE task_id=?",
                                    Long.class,
                                    task))
                    .isEqualTo(4);
            jdbc.update(
                    "UPDATE sn_item SET status='TO_PRINT',current_batch_id=? WHERE sn='G0001'",
                    uuid());
            assertThat(
                            data(b.raw(
                                            "POST",
                                            "/integrations/sn/print-confirmations",
                                            command,
                                            m.token(),
                                            null))
                                    .path("taskId")
                                    .asString())
                    .isEqualTo(task);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM sn_item WHERE sn='G0001'", String.class))
                    .isEqualTo("TO_PRINT");
            jdbc.update("DELETE FROM sn_acquire_member WHERE batch_id=? AND sn='G0002'", batch);
            assertThat(
                            b.raw(
                                            "POST",
                                            "/integrations/sn/print-confirmations",
                                            Map.of(
                                                    "operationId",
                                                    uuid(),
                                                    "batchId",
                                                    batch,
                                                    "targetStatus",
                                                    "PRINTED",
                                                    "ranges",
                                                    List.of(
                                                            Map.of(
                                                                    "startSn", "G0001", "endSn",
                                                                    "G0004"))),
                                            m.token(),
                                            null)
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            b.raw(
                                            "POST",
                                            "/integrations/sn/print-confirmations",
                                            Map.of(
                                                    "operationId",
                                                    uuid(),
                                                    "batchId",
                                                    batch,
                                                    "targetStatus",
                                                    "PRINTED",
                                                    "sns",
                                                    List.of("G0001"),
                                                    "ranges",
                                                    List.of(
                                                            Map.of(
                                                                    "startSn", "G0001", "endSn",
                                                                    "G0001"))),
                                            m.token(),
                                            null)
                                    .statusCode())
                    .isEqualTo(400);
        }
    }

    @Test
    void factoryQueryCanDownloadOriginalHistoryButCrossPiViewCannotGrantAccess() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b), batch = acquire(b, factory);
            String print =
                    data(b.post("/prints", take(uuid(), factory, "PI-1")))
                            .path("taskId")
                            .asString();
            drain();
            String other =
                    jdbc.queryForObject(
                            "SELECT CAST(id AS CHAR) FROM sn_factory WHERE id<>? LIMIT 1",
                            String.class,
                            factory);
            user(11, "local_reader", 5);
            jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(11,?)", factory);
            user(12, "other_reader", 5);
            jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(12,?)", other);
            jdbc.update(
                    "UPDATE sn_item SET"
                            + " status='TO_ACQUIRE',factory_id=?,current_batch_id=NULL,current_print_id=NULL"
                            + " WHERE sn='G0001'",
                    other);
            jdbc.update(
                    "UPDATE sn_item SET"
                            + " status='TO_ACQUIRE',current_batch_id=NULL,current_print_id=NULL WHERE"
                            + " sn='G0002'");
            try (var local = new Browser("local_reader");
                    var foreign = new Browser("other_reader")) {
                assertThat(download(local, print)).isNotEmpty();
                assertThat(members(local, batch)).hasSize(13);
                assertThat(
                                local.raw("GET", "/tasks/" + print, null, local.token, null)
                                        .statusCode())
                        .isEqualTo(404);
                assertThat(
                                data(local.get("/acquisitions/pis?pi=PI-1&piAll=true"))
                                        .path("total")
                                        .asString())
                        .isEqualTo("2");
                assertThat(local.post("/acquisitions", take(uuid(), factory, "PI-1")).statusCode())
                        .isEqualTo(403);
                assertThat(local.get("/acquisitions/pis?pi=UNOWNED&piAll=true").statusCode())
                        .isEqualTo(403);
                assertThat(foreign.get("/acquire-batches/" + batch + "/items").statusCode())
                        .isEqualTo(403);
                assertThat(
                                foreign.raw("GET", "/files/" + print, null, foreign.token, null)
                                        .statusCode())
                        .isEqualTo(403);
                jdbc.update("DELETE FROM sn_user_factory WHERE user_id=11");
                jdbc.update("UPDATE sys_user SET security_version=security_version+1 WHERE id=11");
                assertThat(
                                local.raw("GET", "/files/" + print, null, local.token, null)
                                        .statusCode())
                        .isEqualTo(401);
            }
        }
    }

    @Test
    void serviceReadCursorIsScopedAndGrantRevocationFailsQueuedWorkClosed() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b),
                    other =
                            jdbc.queryForObject(
                                    "SELECT CAST(id AS CHAR) FROM sn_factory WHERE id<>? LIMIT 1",
                                    String.class,
                                    factory);
            var m = mes(b, factory, List.of("sn:acquire:take", "sn:acquire:view", "sn:items:read"));
            assertThat(
                            b.raw(
                                            "GET",
                                            "/integrations/sn/items?factoryId="
                                                    + other
                                                    + "&pi=PI-1",
                                            null,
                                            m.token(),
                                            null)
                                    .statusCode())
                    .isEqualTo(403);
            var page =
                    data(
                            b.raw(
                                    "GET",
                                    "/integrations/sn/items?factoryId="
                                            + factory
                                            + "&pi=PI-1&size=5",
                                    null,
                                    m.token(),
                                    null));
            assertThat(page.path("items").size()).isEqualTo(5);
            String cursor = page.path("nextCursor").asString();
            assertThat(
                            b.raw(
                                            "GET",
                                            "/integrations/sn/items?factoryId="
                                                    + factory
                                                    + "&pi=PI-1&materialMode=EMPTY&cursor="
                                                    + cursor,
                                            null,
                                            m.token(),
                                            null)
                                    .statusCode())
                    .isEqualTo(400);
            String task =
                    data(b.raw(
                                    "POST",
                                    "/integrations/sn/acquisitions",
                                    take(uuid(), factory, "PI-1"),
                                    m.token(),
                                    null))
                            .path("taskId")
                            .asString();
            data(
                    b.raw(
                            "POST",
                            "/system/service-clients/" + m.id() + "/update",
                            Map.of(
                                    "version",
                                    "1",
                                    "enabled",
                                    false,
                                    "grant",
                                    Map.of(
                                            "displayName",
                                            "停用MES",
                                            "factoryIds",
                                            List.of(factory),
                                            "permissions",
                                            List.of("sn:items:read"))),
                            b.token,
                            b.csrf));
            fail(task);
            assertThat(count("sn_acquire_batch")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE'",
                                    Long.class))
                    .isEqualTo(13);
            assertThat(
                            b.raw(
                                            "POST",
                                            "/integrations/auth/token",
                                            Map.of(
                                                    "clientId",
                                                    m.client(),
                                                    "clientSecret",
                                                    m.secret()),
                                            null,
                                            null)
                                    .statusCode())
                    .isEqualTo(401);
            assertThat(
                            b.raw(
                                            "POST",
                                            "/system/service-clients",
                                            Map.of(
                                                    "displayName",
                                                    "不能授管理权限",
                                                    "factoryIds",
                                                    List.of(factory),
                                                    "permissions",
                                                    List.of("system:user:add")),
                                            b.token,
                                            b.csrf)
                                    .statusCode())
                    .isEqualTo(400);
        }
    }

    @Test
    void filePublicationRollbackAndRecoveryNeverUndoOrRepeatPrintFact() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String factory = allocated(b);
            acquire(b, factory);
            String print =
                    data(b.post("/prints", take(uuid(), factory, "PI-1")))
                            .path("taskId")
                            .asString();
            String csv =
                    jdbc.queryForObject(
                            "SELECT csv_task_id FROM sn_print WHERE id=?", String.class, print);
            jdbc.execute("ALTER TABLE sys_file ADD CONSTRAINT ck_s5_ready CHECK(state<>'READY')");
            try {
                var lease = worker.claim(uuid());
                Exception failure =
                        catchThrowableOfType(Exception.class, () -> worker.execute(lease));
                assertThat(failure).isNotNull();
                worker.failed(lease, failure);
            } finally {
                jdbc.execute("ALTER TABLE sys_file DROP CHECK ck_s5_ready");
            }
            String failed =
                    jdbc.queryForObject(
                            "SELECT id FROM sys_task WHERE task_type IN"
                                    + " ('SN_PRINT_CSV','SN_PRINT_XLSX') AND state='FAILED'",
                            String.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM sys_operation WHERE task_id=?",
                                    String.class,
                                    failed))
                    .isEqualTo("SUCCEEDED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='PRINTED'",
                                    Long.class))
                    .isEqualTo(13);
            data(b.raw("POST", "/tasks/" + failed + "/recover", Map.of(), b.token, b.csrf));
            drain();
            assertThat(download(b, print)).isNotEmpty();
            assertThat(download(b, csv)).isNotEmpty();
            assertThat(count("sn_print")).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE"
                                            + " action='sn:print:execute' AND success=TRUE",
                                    Long.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void overTenThousandFixedMembersAndBothFilesKeepLargeSequenceAsText() throws Exception {
        try (var b = new Browser("sn_hq")) {
            sample();
            data(b.post("/orders/sync", Map.of()));
            String
                    bill =
                            jdbc.queryForObject(
                                    "SELECT CAST(id AS CHAR) FROM sn_order_bill WHERE"
                                            + " bill_no='MO-1'",
                                    String.class),
                    factory =
                            jdbc.queryForObject(
                                    "SELECT CAST(factory_id AS CHAR) FROM sn_order_bill WHERE id=?",
                                    String.class,
                                    bill);
            var rule = rule("LARGE", "GENERAL", "", "=甲");
            rule.put("base", 36);
            rule.put("length", 12);
            data(b.post("/rules", rule));
            jdbc.update(
                    "UPDATE sn_order_entry SET qty=10001 WHERE bill_id=? AND source_entry_id='11'",
                    bill);
            var preview =
                    data(
                            b.post(
                                    "/generation/previews",
                                    Map.of(
                                            "billId",
                                            bill,
                                            "quantity",
                                            "10001",
                                            "start",
                                            "9007199254740993")));
            run(generate(b, preview.path("id").asString()));
            run(
                    data(b.post(
                                    "/allocations",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "billId",
                                            bill,
                                            "expectedQuantity",
                                            "10001")))
                            .path("taskId")
                            .asString());
            long began = System.nanoTime();
            String batch = acquire(b, factory);
            long acquired = (System.nanoTime() - began) / 1000000;
            assertThat(count("sn_acquire_member")).isEqualTo(10001);
            String cursor = null;
            long total = 0;
            Set<String> sns = new HashSet<>();
            do {
                var page =
                        data(
                                b.get(
                                        "/acquire-batches/"
                                                + batch
                                                + "/items?size=1000"
                                                + (cursor == null ? "" : "&cursor=" + cursor)));
                for (var item : page.path("items")) {
                    assertThat(item.path("ordinal").asString()).isEqualTo(Long.toString(++total));
                    sns.add(item.path("snapshot").path("sn").asString());
                }
                cursor =
                        page.path("nextCursor").isNull()
                                ? null
                                : page.path("nextCursor").asString();
            } while (cursor != null);
            assertThat(total).isEqualTo(10001);
            assertThat(sns).hasSize(10001);
            began = System.nanoTime();
            String print =
                    data(b.post("/prints", take(uuid(), factory, "PI-1")))
                            .path("taskId")
                            .asString();
            long confirmed = (System.nanoTime() - began) / 1000000;
            drain();
            byte[] xlsx = download(b, print);
            try (var workbook =
                    org.apache.poi.ss.usermodel.WorkbookFactory.create(
                            new java.io.ByteArrayInputStream(xlsx))) {
                var sheet = workbook.getSheetAt(0);
                assertThat(sheet.getLastRowNum()).isEqualTo(10001);
                Set<String> seq = new HashSet<>();
                for (var row : sheet) {
                    if (row.getRowNum() == 0) continue;
                    assertThat(row.getCell(1).getCellType())
                            .isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
                    assertThat(row.getCell(1).getStringCellValue()).startsWith("=甲");
                    assertThat(row.getCell(3).getCellType())
                            .isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
                    seq.add(row.getCell(3).getStringCellValue());
                }
                assertThat(seq).hasSize(10001).contains("9007199254740993", "9007199254750993");
            }
            String csv =
                    jdbc.queryForObject(
                            "SELECT csv_task_id FROM sn_print WHERE id=?", String.class, print);
            String content = new String(download(b, csv), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(content.lines().count()).isEqualTo(10002);
            assertThat(content).contains("\"9007199254740993\"", "\"9007199254750993\"", "\"=甲");
            assertThat(count("sn_print_member")).isEqualTo(10001);
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target/s5-batch-timing.json"),
                    json.writeValueAsString(
                            Map.of(
                                    "quantity",
                                    "10001",
                                    "acquireMs",
                                    acquired,
                                    "printConfirmationMs",
                                    confirmed,
                                    "scope",
                                    "isolated single run; not a throughput acceptance")));
        }
    }

    @Test
    void allFactoryReadCannotExpandLocalTakeAndPrintGrants() throws Exception {
        try (var hq = new Browser("sn_hq")) {
            String factory = allocated(hq);
            jdbc.update(
                    "INSERT INTO sys_role(id,role_code,role_name,data_scope,factory_scope)"
                            + " VALUES(1000,'LOCAL_ACQUIRE','限定领取打印',1,1)");
            jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1000,261),(1000,262)");
            user(10, "mixed_acquire", 4);
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(10,1000)");
            try (var b = new Browser("mixed_acquire")) {
                assertThat(data(b.get("/acquisitions/pis")).path("total").asString())
                        .isEqualTo("1");
                assertThat(b.post("/acquisitions", take(uuid(), factory, "PI-1")).statusCode())
                        .isEqualTo(403);
                assertThat(b.post("/prints", take(uuid(), factory, "PI-1")).statusCode())
                        .isEqualTo(403);
                assertThat(count("sn_acquire_work")).isZero();
                assertThat(count("sn_print")).isZero();
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
