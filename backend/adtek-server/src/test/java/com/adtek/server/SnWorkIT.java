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

/** S4真实HTTP/MySQL/Redis事务、并发和代次回归，手动驱动工作器注入故障；不连接真实ERP。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SnWorkIT {
    static final String SECRET = HexFormat.of().formatHex(new SecureRandom().generateSeed(32)),
            PASSWORD = "SnStage9!" + SECRET.substring(0, 24);
    static final K3ProtocolFixture ERP = new K3ProtocolFixture();

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_sn_stage4_it")
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

    @Autowired com.adtek.sn.service.support.SnWorkSettings workSettings;

    @Test
    void piInputBoundaryRejectsBeforeCreatingAnyWork() throws Exception {
        try (var b = new Browser("sn_hq")) {
            var rejected =
                    b.post(
                            "/pi-initializations",
                            init(uuid(), "P".repeat(129), "1", List.of("OLD")));
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(count("sn_work")).isZero();
            assertThat(count("sys_task")).isZero();
            assertThat(count("sn_key")).isZero();
            var accepted =
                    data(
                            b.post(
                                    "/pi-initializations",
                                    init(uuid(), "P".repeat(128), "1", List.of("OLD"))));
            run(accepted.path("taskId").asString());
            assertThat(count("sn_key")).isEqualTo(1);
            assertThat(last("P".repeat(128))).isZero();
        }
    }

    @Test
    void incomingScanTraversesPagesAndRefusesTruncationAtConfiguredLimit() throws Exception {
        int limit = workSettings.getMaxIncomingScan();
        try (var b = new Browser("sn_hq")) {
            var sns =
                    java.util.stream.IntStream.rangeClosed(1, 1001)
                            .mapToObj(i -> "G" + String.format(java.util.Locale.ROOT, "%04d", i))
                            .toList();
            run(
                    data(b.post("/pi-initializations", init(uuid(), "PI-1", "1", sns)))
                            .path("taskId")
                            .asString());
            String bill = prepared(b);
            workSettings.setMaxIncomingScan(1000);
            var response =
                    b.post(
                            "/generation/previews",
                            Map.of("billId", bill, "quantity", "1", "ruleId", generalRule()));
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(count("sn_preview")).isZero();
            assertThat(last("PI-1")).isZero();
            workSettings.setMaxIncomingScan(limit);
            var preview =
                    data(
                            b.post(
                                    "/generation/previews",
                                    Map.of(
                                            "billId",
                                            bill,
                                            "quantity",
                                            "1",
                                            "ruleId",
                                            generalRule())));
            assertThat(preview.path("firstSn").asString()).isEqualTo("G1002");
            assertThat(count("sn_key")).isEqualTo(1001);
        } finally {
            workSettings.setMaxIncomingScan(limit);
        }
    }

    @Test
    void unboundPiRequiresExplicitSelectionAndOnlySuccessfulGenerationRemembersIt()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            String other =
                    data(b.post("/rules", rule("TEMPLATE", "CUSTOMER", "OTHER", "T")))
                            .path("id")
                            .asString();
            var context = data(b.get("/generation/context?billId=" + bill));
            assertThat(context.path("rule").isNull()).isTrue();
            assertThat(context.path("ruleChoiceRequired").asBoolean()).isTrue();
            assertThat(context.path("issues").toString()).doesNotContain("RULE_MISSING");
            assertThat(
                            data(b.get("/generation/rules?size=1"))
                                    .path("items")
                                    .get(0)
                                    .path("code")
                                    .asString())
                    .isEqualTo("GENERAL");
            assertThat(data(b.get("/generation/rules?q=TEMPLATE")).path("total").asString())
                    .isEqualTo("1");
            var missing = b.post("/generation/previews", Map.of("billId", bill, "quantity", "1"));
            assertThat(missing.statusCode()).isEqualTo(409);
            assertThat(json.readTree(missing.body()).path("code").asInt()).isEqualTo(1004000027);
            assertThat(count("sn_preview")).isZero();
            assertThat(
                            b.post(
                                            "/generation/previews",
                                            Map.of(
                                                    "billId",
                                                    bill,
                                                    "quantity",
                                                    "1",
                                                    "ruleId",
                                                    "1 OR 1=1"))
                                    .statusCode())
                    .isEqualTo(400);
            var preview =
                    data(
                            b.post(
                                    "/generation/previews",
                                    Map.of("billId", bill, "quantity", "2", "ruleId", other)));
            assertThat(preview.path("firstSn").asString()).isEqualTo("T0001");
            assertThat(count("sn_pi_rule_choice")).isZero();
            assertThat(count("sn_pi_counter")).isZero();
            run(generate(b, preview.path("id").asString()));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT CAST(rule_id AS CHAR) FROM sn_pi_rule_choice",
                                    String.class))
                    .isEqualTo(other);
            var chosen = data(b.get("/generation/context?billId=" + bill));
            assertThat(chosen.path("ruleSource").asString()).isEqualTo("CHOSEN");
            assertThat(chosen.path("ruleChoiceRequired").asBoolean()).isFalse();
            assertThat(
                            data(b.post(
                                            "/generation/previews",
                                            Map.of(
                                                    "billId",
                                                    bill,
                                                    "quantity",
                                                    "1",
                                                    "ruleId",
                                                    generalRule())))
                                    .path("firstSn")
                                    .asString())
                    .isEqualTo("T0003");
            jdbc.update("DELETE FROM sn_pi_rule_choice");
            assertThat(data(b.get("/rules/effective?pi=PI-1")).path("prefix").asString())
                    .isEqualTo("T");
            data(b.post("/rules", rule("EXPLICIT", "PI", "PI-1", "P")));
            assertThat(
                            data(b.post(
                                            "/generation/previews",
                                            Map.of("billId", bill, "quantity", "1")))
                                    .path("firstSn")
                                    .asString())
                    .isEqualTo("P0003");
        }
        user(10, "query_rules", 5);
        try (var b = new Browser("query_rules")) {
            assertThat(b.get("/generation/rules").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void anotherChoiceOrNewExplicitBindingInvalidatesOldPreview() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            String other =
                    data(b.post("/rules", rule("TEMPLATE", "CUSTOMER", "OTHER", "T")))
                            .path("id")
                            .asString();
            String stale = preview(b, bill, "1");
            String newer =
                    data(b.post(
                                    "/generation/previews",
                                    Map.of("billId", bill, "quantity", "1", "ruleId", other)))
                            .path("id")
                            .asString();
            run(generate(b, newer));
            assertThat(
                            b.post("/generation", Map.of("operationId", uuid(), "previewId", stale))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(count("sn_work")).isEqualTo(1);
            var next =
                    data(b.post("/generation/previews", Map.of("billId", bill, "quantity", "1")));
            String task = generate(b, next.path("id").asString());
            data(b.post("/rules", rule("EXPLICIT", "PI", "PI-1", "P")));
            assertThat(fail(task)).isEqualTo(1004000016);
            assertThat(count("sn_item")).isEqualTo(1);
            assertThat(last("PI-1")).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT CAST(rule_id AS CHAR) FROM sn_pi_rule_choice",
                                    String.class))
                    .isEqualTo(other);
        }
    }

    @Test
    void generationAuditFailureRollsBackNewRuleChoiceWithNumbersAndQuota() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), task = generate(b, preview(b, bill, "2"));
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_choice_audit"
                            + " CHECK(action<>'sn:generation:execute' OR success=FALSE)");
            try {
                fail(task);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_choice_audit");
            }
            assertThat(count("sn_pi_rule_choice")).isZero();
            assertThat(count("sn_item")).isZero();
            assertThat(count("sn_key")).isZero();
            assertThat(last("PI-1")).isZero();
            assertThat(jdbc.queryForObject("SELECT used FROM sn_rule_version", Boolean.class))
                    .isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(generated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isZero();
            run(generate(b, preview(b, bill, "2")));
            assertThat(count("sn_pi_rule_choice")).isEqualTo(1);
        }
    }

    @Test
    void previouslyUnparsedImportsUseCurrentFormatMaximumWithoutChangingHistoricalOwnership()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            run(
                    data(b.post(
                                    "/pi-initializations",
                                    init(
                                            uuid(),
                                            "PI-1",
                                            "1",
                                            List.of("G0003", "G0600", "OTHER9999"))))
                            .path("taskId")
                            .asString());
            String bill = prepared(b);
            var preview =
                    data(
                            b.post(
                                    "/generation/previews",
                                    Map.of(
                                            "billId",
                                            bill,
                                            "quantity",
                                            "2",
                                            "ruleId",
                                            generalRule())));
            assertThat(preview.path("startSeq").asString()).isEqualTo("601");
            assertThat(preview.path("nextStart").path("bySn").asString()).isEqualTo("G0600");
            assertThat(preview.path("nextStart").path("ownLastSeq").asString()).isEqualTo("0");
            assertThat(last("PI-1")).isZero();
            run(generate(b, preview.path("id").asString()));
            assertThat(last("PI-1")).isEqualTo(602);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_key WHERE seq_pi IS NULL",
                                    Integer.class))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(generated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void incomingMaximumAtCapacityReturnsNullSuggestionAndControlledOverflow() throws Exception {
        try (var b = new Browser("sn_hq")) {
            run(
                    data(b.post("/pi-initializations", init(uuid(), "PI-1", "1", List.of("G9999"))))
                            .path("taskId")
                            .asString());
            String bill = prepared(b);
            data(b.post("/rules", rule("FIXED", "PI", "PI-1", "G")));
            var context = data(b.get("/generation/context?billId=" + bill));
            assertThat(context.path("nextStart").path("startSeq").asString()).isEqualTo("10000");
            assertThat(context.path("nextStart").path("startSn").isNull()).isTrue();
            var response = b.post("/generation/previews", Map.of("billId", bill, "quantity", "1"));
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(1004000002);
            assertThat(count("sn_preview")).isZero();
        }
    }

    String prepared(Browser b) throws Exception {
        sample();
        data(b.post("/orders/sync", Map.of()));
        data(b.post("/rules", rule("GENERAL", "GENERAL", "", "G")));
        return jdbc.queryForObject(
                "SELECT CAST(id AS CHAR) FROM sn_order_bill WHERE bill_no='MO-1'", String.class);
    }

    String generalRule() {
        return jdbc.queryForObject(
                "SELECT CAST(id AS CHAR) FROM sn_rule WHERE bind_scope='GENERAL'", String.class);
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

    @Test
    void fractionalSnapshotBlocksGenerationWithoutLosingHistoryAndCanBeCorrected()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "2")));
            String stale = preview(b, bill, "1");
            ERP.orders =
                    List.of(
                            K3ProtocolFixture.row(
                                    "9007199254740993",
                                    "11",
                                    "MO-1",
                                    "C1",
                                    "PI-1",
                                    "101",
                                    "ERP-A",
                                    "甲工厂",
                                    "M1",
                                    "0.1254"),
                            K3ProtocolFixture.row(
                                    "9007199254740993",
                                    "12",
                                    "MO-1",
                                    "C1",
                                    "PI-1",
                                    "101",
                                    "ERP-A",
                                    "甲工厂",
                                    "M1",
                                    "0.8746"));
            data(b.post("/orders/sync", Map.of()));
            var context = data(b.get("/generation/context?billId=" + bill));
            assertThat(context.path("bill").path("totalQty").asString()).isEqualTo("1");
            assertThat(context.path("bill").path("quantityReady").asBoolean()).isFalse();
            assertThat(context.path("bill").path("quota").asString()).isEqualTo("0");
            assertThat(context.path("issues").toString()).contains("NON_INTEGER_QUANTITY");
            assertThat(
                            b.post("/generation/previews", Map.of("billId", bill, "quantity", "1"))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            b.post("/generation", Map.of("operationId", uuid(), "previewId", stale))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(count("sn_item")).isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(generated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isEqualTo(2);
            ERP.orders =
                    List.of(
                            K3ProtocolFixture.row(
                                    "9007199254740993",
                                    "11",
                                    "MO-1",
                                    "C1",
                                    "PI-1",
                                    "101",
                                    "ERP-A",
                                    "甲工厂",
                                    "M1",
                                    "8"),
                            K3ProtocolFixture.row(
                                    "9007199254740993",
                                    "12",
                                    "MO-1",
                                    "C1",
                                    "PI-1",
                                    "101",
                                    "ERP-A",
                                    "甲工厂",
                                    "M1",
                                    "5"));
            data(b.post("/orders/sync", Map.of()));
            assertThat(
                            data(b.get("/generation/context?billId=" + bill))
                                    .path("bill")
                                    .path("quota")
                                    .asString())
                    .isEqualTo("11");
            run(generate(b, preview(b, bill, "1")));
            assertThat(count("sn_item")).isEqualTo(3);
        }
    }

    @Test
    void historyInitializationIsOncePrintedAtomicAndUnknownSequenceStaysNull() throws Exception {
        try (var b = new Browser("sn_hq")) {
            data(b.post("/rules", rule("GENERAL", "GENERAL", "", "G")));
            String op = uuid();
            var command = init(op, "HISTORY", "20", List.of("G0005", "LEGACY-X", "G0000"));
            String task = data(b.post("/pi-initializations", command)).path("taskId").asString();
            assertThat(count("sn_item")).isZero();
            run(task);
            assertThat(last("HISTORY")).isEqualTo(19);
            assertThat(count("sn_key")).isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='PRINTED' AND"
                                            + " printed_at IS NOT NULL",
                                    Integer.class))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT seq_dec FROM sn_item WHERE sn='LEGACY-X'", Long.class))
                    .isNull();
            assertThat(jdbc.queryForObject("SELECT used FROM sn_rule_version", Boolean.class))
                    .isTrue();
            var status = data(b.get("/pi-initializations?pi=HISTORY"));
            assertThat(status.path("counter").path("importedQty").asString()).isEqualTo("3");
            assertThat(status.path("counter").path("startLocked").asBoolean()).isTrue();
            assertThat(data(b.post("/pi-initializations", command)).path("taskId").asString())
                    .isEqualTo(task);
            assertThat(
                            b.post(
                                            "/pi-initializations",
                                            init(uuid(), "HISTORY", null, List.of("X")))
                                    .statusCode())
                    .isEqualTo(409);
            var history =
                    data(b.get("/pi-initializations/history?pi=HISTORY")).path("items").get(0);
            assertThat(history.path("decodedQty").asString()).isEqualTo("2");
            assertThat(history.path("maxSeq").asString()).isEqualTo("5");
        }
    }

    @Test
    void initializationPreservesAboveJavascriptPrecisionAndNoRuleImport() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String task =
                    data(b.post(
                                    "/pi-initializations",
                                    init(
                                            uuid(),
                                            "PRECISE",
                                            "9007199254740993",
                                            List.of("UNKNOWN"))))
                            .path("taskId")
                            .asString();
            run(task);
            assertThat(
                            data(b.get("/pi-initializations?pi=PRECISE"))
                                    .path("counter")
                                    .path("lastSeq")
                                    .asString())
                    .isEqualTo("9007199254740992");
            assertThat(jdbc.queryForObject("SELECT rule_version_id FROM sn_item", Long.class))
                    .isNull();
            assertThat(
                            b.post("/pi-initializations", init(uuid(), "EMPTY", null, List.of()))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            b.post(
                                            "/pi-initializations",
                                            init(uuid(), "DUP", null, List.of("A", " A ")))
                                    .statusCode())
                    .isEqualTo(409);
        }
    }

    @Test
    void concurrentSameIntentOneTaskAndRedisLossReturnsOriginalResult() throws Exception {
        String operation = uuid();
        var command = init(operation, "CONCURRENT", "100", List.of("OLD"));
        String id;
        try (var b = new Browser("sn_hq");
                var pool = Executors.newFixedThreadPool(6)) {
            List<Future<String>> pending = new ArrayList<>();
            for (int i = 0; i < 6; i++)
                pending.add(
                        pool.submit(
                                () ->
                                        data(b.post("/pi-initializations", command))
                                                .path("taskId")
                                                .asString()));
            Set<String> ids = new HashSet<>();
            for (var f : pending) ids.add(f.get(30, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            id = ids.iterator().next();
            assertThat(count("sn_work")).isEqualTo(1);
            run(id);
            assertThat(
                            b.post(
                                            "/pi-initializations",
                                            init(operation, "CONCURRENT", "101", List.of("OLD")))
                                    .statusCode())
                    .isEqualTo(409);
        }
        try (var c = redis.getConnectionFactory().getConnection()) {
            c.serverCommands().flushDb();
        }
        try (var again = new Browser("sn_hq")) {
            assertThat(data(again.post("/pi-initializations", command)).path("taskId").asString())
                    .isEqualTo(id);
            assertThat(
                            data(again.get("/operations/PI_INIT/" + operation))
                                    .path("operationState")
                                    .asString())
                    .isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void previewIsPureAndGenerationKeepsDuplicateAndEmptyMaterialsWithImmutableSource()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            var p =
                    data(
                            b.post(
                                    "/generation/previews",
                                    Map.of(
                                            "billId",
                                            bill,
                                            "quantity",
                                            "13",
                                            "ruleId",
                                            generalRule())));
            assertThat(p.path("segments").size()).isEqualTo(3);
            assertThat(p.path("segments").get(2).path("material").asString()).isEmpty();
            assertThat(count("sn_pi_counter")).isZero();
            assertThat(count("sn_item")).isZero();
            String task = generate(b, p.path("id").asString());
            run(task);
            assertThat(count("sn_item")).isEqualTo(13);
            assertThat(count("sn_key")).isEqualTo(13);
            assertThat(last("PI-1")).isEqualTo(13);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT DISTINCT source_entry_id FROM sn_item ORDER BY"
                                            + " source_entry_id",
                                    String.class))
                    .containsExactly("11", "12", "13");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE source_system='K3' AND"
                                            + " source_account='ISOLATED' AND"
                                            + " source_bill_id='9007199254740993' AND factory_id IS"
                                            + " NULL AND source_factory_id IS NOT NULL",
                                    Integer.class))
                    .isEqualTo(13);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(generated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isEqualTo(13);
            assertThat(
                            b.post("/pi-initializations", init(uuid(), "PI-1", "30", List.of()))
                                    .statusCode())
                    .isEqualTo(409);
        }
    }

    @Test
    void fullOrderAllocationRejectsPartialAndReplayDoesNotAllocateTwice() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "10")));
            assertThat(
                            b.post(
                                            "/allocations",
                                            Map.of(
                                                    "operationId",
                                                    uuid(),
                                                    "billId",
                                                    bill,
                                                    "expectedQuantity",
                                                    "3"))
                                    .statusCode())
                    .isEqualTo(409);
            var command = Map.of("operationId", uuid(), "billId", bill, "expectedQuantity", "10");
            String task = data(b.post("/allocations", command)).path("taskId").asString();
            run(task);
            assertThat(data(b.post("/allocations", command)).path("taskId").asString())
                    .isEqualTo(task);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE' AND"
                                            + " factory_id=source_factory_id AND allocation_id=?",
                                    Integer.class,
                                    task))
                    .isEqualTo(10);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(allocated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isEqualTo(10);
            assertThat(
                            data(b.get("/generation/context?billId=" + bill))
                                    .path("pendingQuantity")
                                    .asString())
                    .isEqualTo("0");
            run(generate(b, preview(b, bill, "3")));
            String next =
                    data(b.post(
                                    "/allocations",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "billId",
                                            bill,
                                            "expectedQuantity",
                                            "3")))
                            .path("taskId")
                            .asString();
            run(next);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(allocated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isEqualTo(13);
        }
    }

    @Test
    void ruleEditInvalidatesPreviewEvenWhenVersionIdUnchanged() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), p = preview(b, bill, "2");
            var rule = data(b.get("/rules")).path("items").get(0);
            var update = rule("GENERAL", "GENERAL", "", "CHANGED");
            update.put("revision", "1");
            data(b.post("/rules/" + rule.path("id").asString() + "/update", update));
            assertThat(
                            b.post("/generation", Map.of("operationId", uuid(), "previewId", p))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(count("sn_work")).isZero();
            assertThat(count("sys_operation")).isZero();
        }
    }

    @Test
    void queuedWorkRechecksSnapshotAndNeverReleasesConsumedPreview() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), p = preview(b, bill, "3"), id = generate(b, p);
            data(b.post("/orders/sync", Map.of("billNo", "MO-1")));
            assertThat(fail(id)).isEqualTo(1004000016);
            assertThat(count("sn_item")).isZero();
            assertThat(last("PI-1")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT active_task_id FROM sn_pi_counter WHERE pi='PI-1'",
                                    String.class))
                    .isNull();
            assertThat(
                            b.post("/generation", Map.of("operationId", uuid(), "previewId", p))
                                    .statusCode())
                    .isEqualTo(409);
            run(generate(b, preview(b, bill, "3")));
            assertThat(last("PI-1")).isEqualTo(3);
        }
    }

    @Test
    void fullSnConflictRollsBackAllChunksWatermarkQuotaRuleAndTaskResult() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            var before = rule("GENERAL", "GENERAL", "", "G06");
            before.put("length", 2);
            before.put("revision", "1");
            data(b.post("/rules/" + generalRule() + "/update", before));
            String historical =
                    data(b.post("/pi-initializations", init(uuid(), "PI-1", "1", List.of("G0600"))))
                            .path("taskId")
                            .asString();
            run(historical);
            var after = rule("GENERAL", "GENERAL", "", "G");
            after.put("revision", "2");
            data(b.post("/rules/" + generalRule() + "/update", after));
            jdbc.update(
                    "UPDATE sn_order_entry SET qty=800 WHERE bill_id=? AND source_entry_id='11'",
                    Long.parseLong(bill));
            // 旧规则G06+2位把G0600反解为本PI流水0；改为G+4位后第二块遇完整SN冲突，整批回滚。
            String id = generate(b, preview(b, bill, "700"));
            assertThat(fail(id)).isEqualTo(1004000015);
            assertThat(count("sn_pi_rule_choice")).isZero();
            assertThat(count("sn_item")).isEqualTo(1);
            assertThat(count("sn_key")).isEqualTo(1);
            assertThat(last("PI-1")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(generated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT used FROM sn_rule_version WHERE version=2",
                                    Boolean.class))
                    .isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT processed FROM sys_task_progress WHERE task_id=?",
                                    Long.class,
                                    id))
                    .isEqualTo(500);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM sys_operation WHERE task_id=?",
                                    String.class,
                                    id))
                    .isEqualTo("FAILED");
        }
    }

    @Test
    void successfulAuditFailureRollsBackBusinessAndInitializationCanBeResubmitted()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            data(b.post("/rules", rule("GENERAL", "GENERAL", "", "G")));
            String task =
                    data(b.post(
                                    "/pi-initializations",
                                    init(uuid(), "AUDIT", "10", List.of("G0003"))))
                            .path("taskId")
                            .asString();
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_s4_audit"
                            + " CHECK(action<>'sn:pi-init:execute' OR success=FALSE)");
            try {
                fail(task);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_s4_audit");
            }
            assertThat(count("sn_item")).isZero();
            assertThat(count("sn_key")).isZero();
            assertThat(last("AUDIT")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT start_locked FROM sn_pi_counter WHERE pi='AUDIT'",
                                    Boolean.class))
                    .isFalse();
            String retry =
                    data(b.post(
                                    "/pi-initializations",
                                    init(uuid(), "AUDIT", "10", List.of("G0003"))))
                            .path("taskId")
                            .asString();
            run(retry);
            assertThat(last("AUDIT")).isEqualTo(9);
        }
    }

    @Test
    void samePiConcurrentDifferentIntentHasOneOccupancyWithoutOrphanOperation() throws Exception {
        try (var b = new Browser("sn_hq");
                var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () ->
                                    b.post(
                                            "/pi-initializations",
                                            init(uuid(), "SERIAL", "10", List.of())));
            var z =
                    pool.submit(
                            () ->
                                    b.post(
                                            "/pi-initializations",
                                            init(uuid(), "SERIAL", "20", List.of())));
            assertThat(List.of(a.get().statusCode(), z.get().statusCode()))
                    .containsExactlyInAnyOrder(202, 409);
            assertThat(count("sn_work")).isEqualTo(1);
            assertThat(count("sys_operation")).isEqualTo(1);
        }
    }

    @Test
    void staleLeaseCannotCommitAndReclaimedTaskCommitsExactlyOnce() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), id = generate(b, preview(b, bill, "4"));
            var stale = worker.claim(uuid());
            jdbc.update(
                    "UPDATE sys_task SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1"
                            + " SECOND) WHERE id=?",
                    id);
            var fresh = worker.claim(uuid());
            assertThat(fresh.executionEpoch()).isGreaterThan(stale.executionEpoch());
            assertThatThrownBy(() -> worker.execute(stale))
                    .isInstanceOf(com.adtek.common.core.BusinessException.class);
            worker.execute(fresh);
            worker.failed(stale, new java.io.IOException());
            assertThat(count("sn_item")).isEqualTo(4);
            assertThat(last("PI-1")).isEqualTo(4);
            assertThat(tasks.find(id).state().name()).isEqualTo("SUCCESS");
        }
    }

    @Test
    void permissionRevocationAndForeignActorCannotReadOrExecutePendingOperation() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String operation = uuid(),
                    id =
                            data(b.post(
                                            "/pi-initializations",
                                            init(operation, "AUTH", "10", List.of())))
                                    .path("taskId")
                                    .asString();
            user(10, "other", 2);
            try (var other = new Browser("other")) {
                assertThat(other.get("/operations/PI_INIT/" + operation).statusCode())
                        .isEqualTo(404);
            }
            jdbc.update("DELETE FROM sys_user_role WHERE user_id=9007199254741001");
            assertThat(fail(id)).isEqualTo(1003000004);
            assertThat(last("AUTH")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT active_task_id FROM sn_pi_counter WHERE pi='AUTH'",
                                    String.class))
                    .isNull();
        }
    }

    @Test
    void decreasedQuantityReportsAnomalyAndOrgChangeCannotRedirectAllocation() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "6")));
            ERP.orders =
                    List.of(
                            K3ProtocolFixture.row(
                                    "9007199254740993",
                                    "11",
                                    "MO-1",
                                    "C1",
                                    "PI-1",
                                    "102",
                                    "ERP-B",
                                    "乙工厂",
                                    "M1",
                                    "3"));
            data(b.post("/orders/sync", Map.of("billNo", "MO-1")));
            var context = data(b.get("/generation/context?billId=" + bill));
            assertThat(context.path("issues").toString()).contains("QUOTA_ANOMALY");
            assertThat(
                            b.post("/generation/previews", Map.of("billId", bill, "quantity", "1"))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            b.post(
                                            "/allocations",
                                            Map.of(
                                                    "operationId",
                                                    uuid(),
                                                    "billId",
                                                    bill,
                                                    "expectedQuantity",
                                                    "6"))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(generated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isEqualTo(6);
        }
    }

    @Test
    void expiredPreviewAndCapacityFailuresLeaveNoBusinessOrIntentResidue() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), p = preview(b, bill, "2");
            jdbc.update(
                    "UPDATE sn_preview SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1"
                            + " SECOND) WHERE id=?",
                    p);
            assertThat(
                            b.post("/generation", Map.of("operationId", uuid(), "previewId", p))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            b.post(
                                            "/generation/previews",
                                            Map.of(
                                                    "billId",
                                                    bill,
                                                    "quantity",
                                                    "2",
                                                    "start",
                                                    "9999",
                                                    "ruleId",
                                                    generalRule()))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(count("sn_work")).isZero();
            assertThat(count("sn_pi_counter")).isZero();
        }
    }

    @Test
    void quantityOverUiSuggestionAndLargeStartRemainExact() throws Exception {
        try (var b = new Browser("sn_hq")) {
            sample();
            data(b.post("/orders/sync", Map.of()));
            String bill =
                    jdbc.queryForObject(
                            "SELECT CAST(id AS CHAR) FROM sn_order_bill WHERE bill_no='MO-1'",
                            String.class);
            var rule = rule("LARGE", "GENERAL", "", "X");
            rule.put("base", 36);
            rule.put("length", 12);
            data(b.post("/rules", rule));
            jdbc.update(
                    "UPDATE sn_order_entry SET qty=10001 WHERE bill_id=? AND source_entry_id='11'",
                    Long.parseLong(bill));
            var p =
                    data(
                            b.post(
                                    "/generation/previews",
                                    Map.of(
                                            "billId",
                                            bill,
                                            "quantity",
                                            "10001",
                                            "start",
                                            "9007199254740993",
                                            "ruleId",
                                            jdbc.queryForObject(
                                                    "SELECT CAST(id AS CHAR) FROM sn_rule WHERE bind_scope='GENERAL'",
                                                    String.class))));
            assertThat(p.path("endSeq").asString()).isEqualTo("9007199254750993");
            long began = System.nanoTime();
            run(generate(b, p.path("id").asString()));
            long elapsed = (System.nanoTime() - began) / 1000000;
            assertThat(count("sn_item")).isEqualTo(10001);
            assertThat(last("PI-1")).isEqualTo(9007199254750993L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_key k JOIN sn_item i ON"
                                            + " i.gen_month=k.gen_month AND i.id=k.id WHERE i.sn=k.sn"
                                            + " AND i.seq_dec=k.seq_dec",
                                    Long.class))
                    .isEqualTo(10001);
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target/s4-generation-timing.json"),
                    json.writeValueAsString(
                            Map.of(
                                    "quantity",
                                    "10001",
                                    "elapsedMs",
                                    elapsed,
                                    "scope",
                                    "isolated single run; not a throughput acceptance")));
        }
    }

    @Test
    void competingPreviewsSerializeAndNextPreviewContinuesWithoutGap() throws Exception {
        try (var b = new Browser("sn_hq");
                var pool = Executors.newFixedThreadPool(2)) {
            String bill = prepared(b), a = preview(b, bill, "3"), z = preview(b, bill, "4");
            var first =
                    pool.submit(
                            () ->
                                    b.post(
                                            "/generation",
                                            Map.of("operationId", uuid(), "previewId", a)));
            var second =
                    pool.submit(
                            () ->
                                    b.post(
                                            "/generation",
                                            Map.of("operationId", uuid(), "previewId", z)));
            var x = first.get();
            var y = second.get();
            assertThat(List.of(x.statusCode(), y.statusCode())).containsExactlyInAnyOrder(202, 409);
            run(data(x.statusCode() == 202 ? x : y).path("taskId").asString());
            long before = last("PI-1");
            var next =
                    data(
                            b.post(
                                    "/generation/previews",
                                    Map.of(
                                            "billId",
                                            bill,
                                            "quantity",
                                            "2",
                                            "ruleId",
                                            generalRule())));
            assertThat(next.path("startSeq").asString()).isEqualTo(Long.toString(before + 1));
            run(generate(b, next.path("id").asString()));
            assertThat(last("PI-1")).isEqualTo(before + 2);
        }
    }

    @Test
    void cancellationReleasesPiButNeverReusesPreview() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), p = preview(b, bill, "3"), id = generate(b, p);
            var response = b.raw("POST", "/tasks/" + id + "/cancel", Map.of(), b.token, b.csrf);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(count("sn_item")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT active_task_id FROM sn_pi_counter WHERE pi='PI-1'",
                                    String.class))
                    .isNull();
            assertThat(
                            b.post("/generation", Map.of("operationId", uuid(), "previewId", p))
                                    .statusCode())
                    .isEqualTo(409);
            run(generate(b, preview(b, bill, "3")));
        }
    }

    @Test
    void allocationSuccessAuditFailureRollsBackMembersAndLedger() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "13")));
            String id =
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
                            .asString();
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_s4_alloc_audit"
                            + " CHECK(action<>'sn:allocation:execute' OR success=FALSE)");
            try {
                fail(id);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_s4_alloc_audit");
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='PENDING_ALLOC' AND"
                                            + " factory_id IS NULL AND allocation_id IS NULL",
                                    Integer.class))
                    .isEqualTo(13);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(allocated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isZero();
            assertThat(last("PI-1")).isEqualTo(13);
        }
    }

    @Test
    void localWriteScopeCannotBorrowAllFactoryReadScope() throws Exception {
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope,factory_scope)"
                        + " VALUES(1000,'LOCAL_SN','限定SN操作',1,1)");
        jdbc.update(
                "INSERT INTO sys_role_menu(role_id,menu_id)"
                        + " VALUES(1000,241),(1000,252),(1000,253)");
        user(10, "mixed_sn", 4);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(10,1000)");
        try (var b = new Browser("mixed_sn")) {
            assertThat(
                            b.post("/pi-initializations", init(uuid(), "SCOPED", "10", List.of()))
                                    .statusCode())
                    .isEqualTo(403);
            assertThat(
                            b.post(
                                            "/generation",
                                            Map.of("operationId", uuid(), "previewId", uuid()))
                                    .statusCode())
                    .isEqualTo(403);
            assertThat(
                            b.post(
                                            "/allocations",
                                            Map.of(
                                                    "operationId",
                                                    uuid(),
                                                    "billId",
                                                    "1",
                                                    "expectedQuantity",
                                                    "1"))
                                    .statusCode())
                    .isEqualTo(403);
            assertThat(count("sn_work")).isZero();
        }
    }

    /** 分三档运行真实生成/整单分配和分页查询；单次本机样本只记录耗时，不冒充p95或亿级容量。 */
    @Test
    void capacityProfileAtOneTenAndOneHundredThousandPreservesAllLedgers() throws Exception {
        var samples = new ArrayList<Map<String, Object>>();
        long total = 0;
        try (var b = new Browser("sn_hq")) {
            var format = rule("CAPACITY", "GENERAL", "", "C");
            format.put("length", 8);
            data(b.post("/rules", format));
            for (int quantity : List.of(1000, 10000, 100000)) {
                String qty = Integer.toString(quantity), pi = "CAP-" + qty, no = "CAP-MO-" + qty;
                ERP.orders =
                        List.of(
                                K3ProtocolFixture.row(
                                        qty, "1", no, "C1", pi, "101", "ERP-A", "合成容量工厂", "M1",
                                        qty));
                data(b.post("/orders/sync", Map.of()));
                String bill =
                        jdbc.queryForObject(
                                "SELECT CAST(id AS CHAR) FROM sn_order_bill WHERE bill_no=?",
                                String.class,
                                no);
                long waits =
                        Long.parseLong(
                                jdbc.queryForMap("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'")
                                        .get("Value")
                                        .toString());
                long began = System.nanoTime();
                String p = preview(b, bill, qty);
                long previewMs = (System.nanoTime() - began) / 1000000;
                began = System.nanoTime();
                String generation = generate(b, p);
                long submitMs = (System.nanoTime() - began) / 1000000;
                began = System.nanoTime();
                run(generation);
                long generationMs = (System.nanoTime() - began) / 1000000;
                String allocation =
                        data(b.post(
                                        "/allocations",
                                        Map.of(
                                                "operationId",
                                                uuid(),
                                                "billId",
                                                bill,
                                                "expectedQuantity",
                                                qty)))
                                .path("taskId")
                                .asString();
                String auditSql =
                        com.adtek.sn.mapper.SnAuditContextMapper.class
                                .getMethod("allocation", String.class)
                                .getAnnotation(org.apache.ibatis.annotations.Select.class)
                                .value()[0]
                                .replace("#{id}", "?");
                var auditPlan =
                        planTable(
                                json.readTree(
                                        jdbc.queryForObject(
                                                "EXPLAIN FORMAT=JSON " + auditSql,
                                                String.class,
                                                allocation)),
                                "i");
                assertThat(auditPlan)
                        .as("allocation audit must use full SN primary key")
                        .isNotNull();
                assertThat(auditPlan.path("access_type").asString()).isEqualTo("eq_ref");
                assertThat(auditPlan.path("used_key_parts"))
                        .isEqualTo(json.valueToTree(List.of("gen_month", "id")));
                began = System.nanoTime();
                run(allocation);
                long allocationMs = (System.nanoTime() - began) / 1000000;
                began = System.nanoTime();
                var page = data(b.get("/items?size=100&pi=" + pi));
                long pageMs = (System.nanoTime() - began) / 1000000;
                assertThat(page.path("items").size()).isEqualTo(100);
                assertThat(page.path("nextCursor").isNull()).isFalse();
                began = System.nanoTime();
                assertThat(data(b.get("/items/count?pi=" + pi)).path("quantity").asString())
                        .isEqualTo(qty);
                long countMs = (System.nanoTime() - began) / 1000000;
                assertThat(last(pi)).isEqualTo(quantity);
                assertThat(tasks.find(generation).state().name()).isEqualTo("SUCCESS");
                assertThat(tasks.find(allocation).state().name()).isEqualTo("SUCCESS");
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sn_key k JOIN sn_item i ON"
                                                + " i.gen_month=k.gen_month AND i.id=k.id WHERE i.pi=?"
                                                + " AND i.pi=k.pi AND i.sn=k.sn AND i.seq_pi=k.seq_pi"
                                                + " AND i.seq_dec=k.seq_dec AND i.status='TO_ACQUIRE'"
                                                + " AND i.allocation_id=? AND"
                                                + " i.factory_id=i.source_factory_id",
                                        Long.class,
                                        pi,
                                        allocation))
                        .isEqualTo(quantity);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(DISTINCT seq_dec) FROM sn_key WHERE seq_pi=?",
                                        Long.class,
                                        pi))
                        .isEqualTo(quantity);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT MIN(seq_dec) FROM sn_key WHERE seq_pi=?",
                                        Long.class,
                                        pi))
                        .isEqualTo(1);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT MAX(seq_dec) FROM sn_key WHERE seq_pi=?",
                                        Long.class,
                                        pi))
                        .isEqualTo(quantity);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT SUM(generated_qty) FROM sn_source_entry_quota WHERE"
                                                + " bill_id=?",
                                        Long.class,
                                        bill))
                        .isEqualTo(quantity);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT SUM(allocated_qty) FROM sn_source_entry_quota WHERE"
                                                + " bill_id=?",
                                        Long.class,
                                        bill))
                        .isEqualTo(quantity);
                long lockWaits =
                        Long.parseLong(
                                        jdbc.queryForMap(
                                                        "SHOW GLOBAL STATUS LIKE"
                                                                + " 'Innodb_row_lock_waits'")
                                                .get("Value")
                                                .toString())
                                - waits;
                samples.add(
                        Map.of(
                                "quantity",
                                qty,
                                "previewMs",
                                previewMs,
                                "submitMs",
                                submitMs,
                                "generationMs",
                                generationMs,
                                "allocationMs",
                                allocationMs,
                                "first100QueryMs",
                                pageMs,
                                "exactCountMs",
                                countMs,
                                "mysqlRowLockWaits",
                                lockWaits,
                                "auditAccess",
                                "eq_ref",
                                "auditKeyParts",
                                List.of("gen_month", "id")));
                total += quantity;
            }
        }
        assertThat(count("sn_item")).isEqualTo(total);
        assertThat(count("sn_key")).isEqualTo(total);
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/sn-capacity-profile.json"),
                json.writeValueAsString(
                        Map.of(
                                "samples",
                                samples,
                                "totalItems",
                                Long.toString(total),
                                "java",
                                System.getProperty("java.version"),
                                "processors",
                                Runtime.getRuntime().availableProcessors(),
                                "maxHeapBytes",
                                Runtime.getRuntime().maxMemory(),
                                "database",
                                "MySQL 8.4.11 Testcontainers",
                                "scope",
                                "synthetic serial local single samples; no production SLO or"
                                        + " 150-million-row claim")));
    }

    private static JsonNode planTable(JsonNode node, String alias) {
        if (node.isObject() && alias.equals(node.path("table_name").asString())) return node;
        for (var child : node) {
            var found = planTable(child, alias);
            if (found != null) return found;
        }
        return null;
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
