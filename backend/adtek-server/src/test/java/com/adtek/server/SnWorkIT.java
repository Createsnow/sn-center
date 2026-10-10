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
        workSettings.setInlineGenerationQuantity(0);
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
            assertThat(count("sn_bill_generation")).isZero();
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

    void changedOrder(Browser b, String pi, String customer, String org, String code, String name)
            throws Exception {
        ERP.orders =
                List.of(
                        K3ProtocolFixture.row(
                                "9007199254740993",
                                "11",
                                "MO-1",
                                customer,
                                pi,
                                org,
                                code,
                                name,
                                "M1",
                                "13"));
        data(b.post("/orders/sync", Map.of("billNo", "MO-1")));
    }

    @Test
    void changedOrganizationRequiresLatestSuccessfulGenerationBeforeMixedSourceAllocation()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "2")));
            changedOrder(b, "PI-1", "C-NEW", "102", "ERP-B", "乙工厂");
            var rejection =
                    b.post(
                            "/allocations",
                            Map.of(
                                    "operationId",
                                    uuid(),
                                    "billId",
                                    bill,
                                    "expectedQuantity",
                                    "2",
                                    "factoryCode",
                                    "ERP-B"));
            assertThat(rejection.statusCode()).isEqualTo(409);
            assertThat(json.readTree(rejection.body()).path("params").path("reason").asString())
                    .isEqualTo("ALLOC_FACTORY_MISMATCH");
            assertThat(count("sn_work")).isEqualTo(1);
            var context = data(b.get("/generation/context?billId=" + bill));
            assertThat(context.path("issues").toString())
                    .doesNotContain("ALLOCATION_SOURCE_CHANGED");
            assertThat(context.path("piSummary").get(0).path("generatedQty").asString())
                    .isEqualTo("0");
            run(generate(b, preview(b, bill, "3")));
            String task =
                    data(b.post(
                                    "/allocations",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "billId",
                                            bill,
                                            "expectedQuantity",
                                            "5",
                                            "quantity",
                                            "3",
                                            "factoryCode",
                                            "ERP-B")))
                            .path("taskId")
                            .asString();
            run(task);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT first_customer FROM sn_bill_generation WHERE bill_id=?",
                                    String.class,
                                    bill))
                    .isEqualTo("C1");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item i JOIN sn_factory f ON f.id=i.factory_id"
                                            + " WHERE i.allocation_id=? AND f.code='ERP-B'",
                                    Long.class,
                                    task))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT customer FROM sn_item WHERE allocation_id=? ORDER BY seq_dec",
                                    String.class,
                                    task))
                    .containsExactly("C1", "C1", "C-NEW");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(DISTINCT source_factory_id) FROM sn_item WHERE allocation_id=?",
                                    Long.class,
                                    task))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT customer FROM sn_audit WHERE action='sn:allocation:execute'"
                                            + " AND operation_id=(SELECT operation_id FROM sys_operation WHERE task_id=?)",
                                    String.class,
                                    task))
                    .isEqualTo("C1");
            assertThat(
                            data(b.get("/generation/context?billId=" + bill))
                                    .path("pendingQuantity")
                                    .asString())
                    .isEqualTo("2");
        }
    }

    @Test
    void changedPiPreservesFirstLedgerAndAllocationIdentityWhileMembersKeepActualPi()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "2")));
            changedOrder(b, "PI-CHANGED", "C-NEW", "101", "ERP-A", "甲工厂");
            var p =
                    data(
                            b.post(
                                    "/generation/previews",
                                    Map.of(
                                            "billId",
                                            bill,
                                            "quantity",
                                            "2",
                                            "start",
                                            "10",
                                            "ruleId",
                                            generalRule())));
            run(generate(b, p.path("id").asString()));
            var context = data(b.get("/generation/context?billId=" + bill));
            assertThat(context.path("bill").path("generatedQty").asString()).isEqualTo("4");
            assertThat(context.path("piSummary").get(0).path("generatedQty").asString())
                    .isEqualTo("0");
            String task =
                    data(b.post(
                                    "/allocations",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "billId",
                                            bill,
                                            "expectedQuantity",
                                            "4",
                                            "factoryCode",
                                            "ERP-A")))
                            .path("taskId")
                            .asString();
            // 接受后的 ERP 快照再变化，不应改写已固定的分配身份与成员。
            changedOrder(b, "PI-CHANGED-AGAIN", "C-LATEST", "102", "ERP-B", "乙工厂");
            run(task);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT pi FROM sn_work WHERE task_id=?", String.class, task))
                    .isEqualTo("PI-1");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT pi FROM sn_item WHERE allocation_id=? ORDER BY seq_dec",
                                    String.class,
                                    task))
                    .containsExactly("PI-1", "PI-1", "PI-CHANGED", "PI-CHANGED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.customer'))"
                                            + " FROM sn_work WHERE task_id=?",
                                    String.class,
                                    task))
                    .isEqualTo("C1");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_pi_counter WHERE active_task_id IS NOT NULL",
                                    Long.class))
                    .isZero();
        }
    }

    @Test
    void overlappingPiSequencesAllocateOlderMembersBeforeLexicallySmallerRandomIds()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "3")));
            changedOrder(b, "PI-CHANGED", "C-NEW", "101", "ERP-A", "甲工厂");
            data(b.post("/rules", rule("CHANGED-PI", "PI", "PI-CHANGED", "H")));
            run(generate(b, preview(b, bill, "2")));

            // 只在一次性测试库构造相反 UUID 字典序，避免回归是否失败取决于随机 ID。
            for (var item : jdbc.queryForList("SELECT id,pi,seq_dec FROM sn_item")) {
                String original = item.get("id").toString();
                String replacement =
                        (item.get("pi").equals("PI-1") ? "ffffffff" : "00000000")
                                + "-0000-4000-8000-"
                                + String.format("%012d", item.get("seq_dec"));
                jdbc.update("UPDATE sn_key SET id=? WHERE id=?", replacement, original);
                jdbc.update("UPDATE sn_item SET id=? WHERE id=?", replacement, original);
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT MAX(created_at)<(SELECT MIN(created_at) FROM sn_item WHERE pi='PI-CHANGED')"
                                            + " FROM sn_item WHERE pi='PI-1'",
                                    Boolean.class))
                    .isTrue();
            String task =
                    data(b.post(
                                    "/allocations",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "billId",
                                            bill,
                                            "expectedQuantity",
                                            "5",
                                            "quantity",
                                            "3",
                                            "factoryCode",
                                            "ERP-A")))
                            .path("taskId")
                            .asString();
            run(task);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT sn FROM sn_item WHERE allocation_id=? ORDER BY seq_dec,created_at,id",
                                    String.class,
                                    task))
                    .containsExactly("G0001", "H0001", "G0002");
            var first =
                    jdbc.queryForMap(
                            "SELECT first_sn,last_sn,pi FROM sn_work WHERE task_id=?", task);
            assertThat(first)
                    .containsEntry("first_sn", "G0001")
                    .containsEntry("last_sn", "G0002")
                    .containsEntry("pi", "PI-1");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT sn FROM sn_item WHERE status='PENDING_ALLOC' ORDER BY sn",
                                    String.class))
                    .containsExactly("G0003", "H0002");
            String remainder =
                    data(b.post(
                                    "/allocations",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "billId",
                                            bill,
                                            "expectedQuantity",
                                            "2",
                                            "factoryCode",
                                            "ERP-A")))
                            .path("taskId")
                            .asString();
            run(remainder);
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT first_sn,last_sn FROM sn_work WHERE task_id=?",
                                    remainder))
                    .containsEntry("first_sn", "H0002")
                    .containsEntry("last_sn", "G0003");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(allocated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isEqualTo(5);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item i JOIN sn_key k ON k.id=i.id AND k.gen_month=i.gen_month",
                                    Long.class))
                    .isEqualTo(5);
        }
    }

    @Test
    void summaryUsesHistoricalIdentityAndZeroPlanForOrdersOutsideCurrentScope() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "2")));
            ERP.orders =
                    List.of(
                            K3ProtocolFixture.row(
                                    "77",
                                    "771",
                                    "MO-SIBLING",
                                    "C1",
                                    "PI-1",
                                    "101",
                                    "ERP-A",
                                    "甲工厂",
                                    "M1",
                                    "5"),
                            K3ProtocolFixture.row(
                                    "78",
                                    "781",
                                    "MO-UNUSED",
                                    "C1",
                                    "PI-1",
                                    "101",
                                    "ERP-A",
                                    "甲工厂",
                                    "M1",
                                    "7"));
            data(b.post("/orders/sync", Map.of()));
            String sibling =
                    jdbc.queryForObject(
                            "SELECT CAST(id AS CHAR) FROM sn_order_bill WHERE bill_no='MO-SIBLING'",
                            String.class);
            ERP.orders = List.of(ERP.orders.getFirst());
            data(b.post("/orders/sync", Map.of()));
            var summary = data(b.get("/generation/context?billId=" + sibling)).path("piSummary");
            assertThat(summary.size()).isEqualTo(2);
            assertThat(summary.get(0).path("billNo").asString()).isEqualTo("MO-SIBLING");
            var historical = summary.get(1);
            assertThat(historical.path("billNo").asString()).isEqualTo("MO-1");
            assertThat(historical.path("inSnapshot").asBoolean()).isFalse();
            assertThat(historical.path("totalQty").asString()).isEqualTo("0");
            assertThat(historical.path("quota").asString()).isEqualTo("0");
            assertThat(historical.path("generatedQty").asString()).isEqualTo("2");
            // 即使仍在快照，改 PI 的原订单在旧 PI 汇总里也是历史行。
            changedOrder(b, "PI-OTHER", "C-NEW", "102", "ERP-B", "乙工厂");
            summary = data(b.get("/generation/context?billId=" + sibling)).path("piSummary");
            assertThat(summary.get(1).path("inSnapshot").asBoolean()).isFalse();
            assertThat(summary.get(1).path("totalQty").asString()).isEqualTo("0");
            assertThat(summary.toString()).doesNotContain("MO-UNUSED");
        }
    }

    @Test
    void ledgerBackfillUsesCommittedAuditIncludingArchiveAndNeverCurrentCustomer()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), previewId = preview(b, bill, "2");
            // 预演客户已过时但语义号段未变：首次客户必须来自实际成功事务。
            changedOrder(b, "PI-1", "C-ACTUAL", "101", "ERP-A", "甲工厂");
            String first = generate(b, previewId);
            run(first);
            changedOrder(b, "PI-1", "C-LATER", "102", "ERP-B", "乙工厂");
            String latest = generate(b, preview(b, bill, "1"));
            run(latest);
            jdbc.update(
                    "INSERT INTO sn_audit_archive SELECT * FROM sn_audit WHERE action='sn:generation:execute'");
            jdbc.update("DELETE FROM sn_audit WHERE action='sn:generation:execute'");
            jdbc.update("DELETE FROM sn_bill_generation");
            String sql =
                    new org.springframework.core.io.ClassPathResource(
                                    "db/migration/V28__bill_generation_identity.sql")
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            jdbc.execute(sql.substring(sql.indexOf("INSERT INTO sn_bill_generation")));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT first_customer FROM sn_bill_generation WHERE bill_id=?",
                                    String.class,
                                    bill))
                    .isEqualTo("C-ACTUAL");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT first_task_id FROM sn_bill_generation WHERE bill_id=?",
                                    String.class,
                                    bill))
                    .isEqualTo(first);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_task_id FROM sn_bill_generation WHERE bill_id=?",
                                    String.class,
                                    bill))
                    .isEqualTo(latest);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT f.code FROM sn_bill_generation g JOIN sn_factory f ON f.id=g.factory_id WHERE bill_id=?",
                                    String.class,
                                    bill))
                    .isEqualTo("ERP-B");
            // 缺失历史不猜补；拒绝分配，不改成员和额度。
            jdbc.update("DELETE FROM sn_audit_archive WHERE action='sn:generation:execute'");
            jdbc.update("DELETE FROM sn_bill_generation");
            jdbc.execute(sql.substring(sql.indexOf("INSERT INTO sn_bill_generation")));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT first_customer FROM sn_bill_generation WHERE bill_id=?",
                                    String.class,
                                    bill))
                    .isNull();
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
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='PENDING_ALLOC'",
                                    Long.class))
                    .isEqualTo(3);
        }
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
    void inlineGenerationCommitsFailureHistoryBeforeReturningBusinessErrorAndCanRecover()
            throws Exception {
        workSettings.setInlineGenerationQuantity(5000);
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), preview = preview(b, bill, "3"), operation = uuid();
            ERP.orders = List.of(row("9007199254740993", "11", "MO-1", "CHANGED", "13"));
            data(b.post("/orders/sync", Map.of()));
            var command = Map.of("operationId", operation, "previewId", preview);
            var rejected = b.post("/generation", command);
            assertThat(rejected.statusCode()).isEqualTo(409);
            assertThat(json.readTree(rejected.body()).path("params").path("reason").asString())
                    .isEqualTo("GEN_PREVIEW_CHANGED");
            var failed = data(b.get("/generation/context?billId=" + bill)).path("jobs").get(0);
            assertThat(failed.path("state").asString()).isEqualTo("FAILED");
            assertThat(failed.path("firstSn").asString()).isEqualTo("G0001");
            assertThat(failed.path("lastSn").asString()).isEqualTo("G0003");
            assertThat(failed.path("quantity").asString()).isEqualTo("3");
            assertThat(failed.path("processed").asString()).isEqualTo("0");
            assertThat(failed.path("actorName").asString()).isEqualTo("sn_hq");
            assertThat(failed.path("errorParams").path("reason").asString())
                    .isEqualTo("GEN_PREVIEW_CHANGED");
            assertThat(count("sn_item")).isZero();
            assertThat(last("PI-1")).isZero();
            var replay = data(b.post("/generation", command));
            assertThat(replay.path("state").asString()).isEqualTo("FAILED");
            assertThat(replay.path("taskId").asString())
                    .isEqualTo(failed.path("taskId").asString());
            assertThat(count("sn_work")).isEqualTo(1);
            var success =
                    data(
                            b.post(
                                    "/generation",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "previewId",
                                            preview(b, bill, "3"))));
            assertThat(success.path("state").asString()).isEqualTo("SUCCESS");
            assertThat(count("sn_work")).isEqualTo(2);
            assertThat(last("PI-1")).isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT sn FROM sn_item ORDER BY seq_dec", String.class))
                    .containsExactly("G0001", "G0002", "G0003");
        }
    }

    @Test
    void inlineGenerationClaimsOnlyItsOwnNewTaskAndNeverStealsAnExistingLease() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            String older =
                    data(b.post("/pi-initializations", init(uuid(), "OTHER-PI", "1", List.of())))
                            .path("taskId")
                            .asString();
            workSettings.setInlineGenerationQuantity(5000);
            var command = Map.of("operationId", uuid(), "previewId", preview(b, bill, "2"));
            var submitted = data(b.post("/generation", command));
            assertThat(submitted.path("state").asString()).isEqualTo("SUCCESS");
            assertThat(tasks.find(older).state().name()).isEqualTo("QUEUED");
            assertThat(worker.claimSubmitted(submitted.path("taskId").asString(), uuid())).isNull();
            assertThat(data(b.post("/generation", command)).path("taskId").asString())
                    .isEqualTo(submitted.path("taskId").asString());
            assertThat(count("sn_item")).isEqualTo(2);
            var lease = worker.claimSubmitted(older, uuid());
            assertThat(lease).isNotNull();
            assertThat(worker.claimSubmitted(older, uuid())).isNull();
            worker.execute(lease);
        }
    }

    @Test
    void previewPreflightReasonsLeaveNoNewJobAndAcceptedWorkSurvivesPreviewExpiry()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), expired = preview(b, bill, "2");
            jdbc.update(
                    "UPDATE sn_preview SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",
                    expired);
            var rejected =
                    b.post("/generation", Map.of("operationId", uuid(), "previewId", expired));
            assertThat(rejected.statusCode()).isEqualTo(409);
            assertThat(json.readTree(rejected.body()).path("params").path("reason").asString())
                    .isEqualTo("GEN_PREVIEW_EXPIRED");
            assertThat(count("sn_work")).isZero();
            String stale = preview(b, bill, "2"),
                    accepted = preview(b, bill, "2"),
                    task = generate(b, accepted);
            jdbc.update(
                    "UPDATE sn_preview SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",
                    accepted);
            run(task);
            var used = b.post("/generation", Map.of("operationId", uuid(), "previewId", accepted));
            assertThat(used.statusCode()).isEqualTo(409);
            assertThat(json.readTree(used.body()).path("params").path("reason").asString())
                    .isEqualTo("GEN_PREVIEW_USED");
            var changed = b.post("/generation", Map.of("operationId", uuid(), "previewId", stale));
            assertThat(changed.statusCode()).isEqualTo(409);
            var params = json.readTree(changed.body()).path("params");
            assertThat(params.path("reason").asString()).isEqualTo("GEN_PREVIEW_STALE");
            assertThat(params.path("expected").asString()).isEqualTo("0");
            assertThat(params.path("actual").asString()).isEqualTo("2");
            assertThat(count("sn_work")).isEqualTo(1);
            assertThat(count("sn_item")).isEqualTo(2);
        }
    }

    @Test
    void rolledBackProgressAndUnsafeFailureParametersNeverBecomeGeneratedHistory()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), task = generate(b, preview(b, bill, "3"));
            var lease = worker.claimSubmitted(task, uuid());
            tasks.progressWrite(task, lease.executionEpoch(), 2, "WRITING");
            worker.failed(
                    lease,
                    new com.adtek.common.core.BusinessException(
                            com.adtek.sn.constant.ErrorCodeConstants.PREVIEW_CHANGED,
                            Map.of(
                                    "reason",
                                    "GEN_PREVIEW_STALE",
                                    "pi",
                                    "PI-1",
                                    "expected",
                                    "9007199254740993",
                                    "actual",
                                    "9007199254740994",
                                    "secret",
                                    "must-not-persist")));
            var failed = data(b.get("/generation/context?billId=" + bill)).path("jobs").get(0);
            assertThat(failed.path("processed").asString()).isEqualTo("0");
            assertThat(failed.path("errorParams").path("actual").asString())
                    .isEqualTo("9007199254740994");
            assertThat(failed.path("errorParams").has("secret")).isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT error_params FROM sn_work WHERE task_id=?",
                                    String.class,
                                    task))
                    .doesNotContain("must-not-persist");
            var dashboard = data(b.get("/dashboard"));
            assertThat(dashboard.path("recentJobs").get(0).path("processed").asString())
                    .isEqualTo("0");
            assertThat(count("sn_item")).isZero();
        }
    }

    @Test
    void fractionalSnapshotKeepsOriginalDecimalsAndRejectsInsufficientIntegerQuota()
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
            assertThat(context.path("bill").path("totalQty").asString()).isEqualTo("0");
            assertThat(context.path("bill").path("quantityReady").asBoolean()).isTrue();
            assertThat(context.path("bill").path("quota").asString()).isEqualTo("0");
            assertThat(context.path("issues").toString())
                    .contains("QUOTA_EMPTY")
                    .doesNotContain("NON_INTEGER_QUANTITY");
            assertThat(
                            b.post("/generation/previews", Map.of("billId", bill, "quantity", "1"))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(fail(generate(b, stale))).isEqualTo(1004000018);
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
    void changedErpLinesUseWholeBillQuotaAndSkipWhileKeepingIssuedSources() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "5")));
            var issued = jdbc.queryForList("SELECT * FROM sn_item ORDER BY seq_dec");
            String stale = preview(b, bill, "4");

            // 原来首行已发 5 枚；ERP 缩为 2 枚并替换余下分录后，整单只剩 9 - 5 = 4 枚。
            ERP.orders =
                    List.of(
                            row("9007199254740993", "11", "MO-1", "M1", "2.5"),
                            row("9007199254740993", "14", "MO-1", "NEW", "7.9"));
            data(b.post("/orders/sync", Map.of()));
            var context = data(b.get("/generation/context?billId=" + bill));
            assertThat(context.path("bill").path("quota").asString()).isEqualTo("4");
            assertThat(context.path("bill").path("generatedQty").asString()).isEqualTo("5");
            assertThat(context.path("piSummary").get(0).path("quota").asString()).isEqualTo("4");
            assertThat(context.path("issues").size()).isZero();
            assertThat(fail(generate(b, stale))).isEqualTo(1004000016);
            assertThat(
                            b.post("/generation/previews", Map.of("billId", bill, "quantity", "5"))
                                    .statusCode())
                    .isEqualTo(409);
            String token = preview(b, bill, "4");
            var plan =
                    jdbc.queryForObject(
                            "SELECT payload FROM sn_preview WHERE id=?", String.class, token);
            var segments = json.readTree(plan).path("segments");
            assertThat(segments.size()).isEqualTo(1);
            assertThat(segments.get(0).path("entryId").asString()).isEqualTo("14");
            assertThat(segments.get(0).path("quantity").asLong()).isEqualTo(4);
            run(generate(b, token));
            assertThat(jdbc.queryForList("SELECT * FROM sn_item WHERE seq_dec<=5 ORDER BY seq_dec"))
                    .isEqualTo(issued);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT sn FROM sn_item WHERE source_entry_id='14' ORDER BY seq_dec",
                                    String.class))
                    .containsExactly("G0006", "G0007", "G0008", "G0009");

            // 再删除所有旧分录仍保留累计；换成 12 枚的新分录时只能继续生成 3 枚。
            ERP.orders = List.of(row("9007199254740993", "15", "MO-1", "REPLACED", "12.4"));
            data(b.post("/orders/sync", Map.of()));
            assertThat(
                            data(b.get("/generation/context?billId=" + bill))
                                    .path("bill")
                                    .path("quota")
                                    .asString())
                    .isEqualTo("3");
            run(generate(b, preview(b, bill, "3")));
            assertThat(count("sn_item")).isEqualTo(12);
            assertThat(
                            data(b.get("/generation/context?billId=" + bill))
                                    .path("bill")
                                    .path("quota")
                                    .asString())
                    .isEqualTo("0");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(generated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isEqualTo(12);
        }
    }

    @Test
    void reorderedErpLinesSplitByCurrentOrdinalAfterWholeBillOffset() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "5")));
            ERP.orders =
                    List.of(
                            row("9007199254740993", "13", "MO-1", "", "4.7"),
                            row("9007199254740993", "12", "MO-1", "M1", "3.8"),
                            row("9007199254740993", "11", "MO-1", "M1", "8.9"));
            data(b.post("/orders/sync", Map.of()));
            var p = data(b.post("/generation/previews", Map.of("billId", bill, "quantity", "5")));
            assertThat(p.path("segments").size()).isEqualTo(2);
            assertThat(p.path("segments").get(0).path("sourceEntryId").asString()).isEqualTo("12");
            assertThat(p.path("segments").get(0).path("quantity").asString()).isEqualTo("2");
            assertThat(p.path("segments").get(1).path("sourceEntryId").asString()).isEqualTo("11");
            assertThat(p.path("segments").get(1).path("quantity").asString()).isEqualTo("3");
            run(generate(b, p.path("id").asString()));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE source_entry_id='13'",
                                    Long.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT generated_qty FROM sn_source_entry_quota WHERE source_entry_id='11'",
                                    Long.class))
                    .isEqualTo(8);
        }
    }

    @Test
    void fractionalLinesGenerateTheirIntegerPartsWithoutCombiningRemainders() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            ERP.orders =
                    List.of(
                            row("9007199254740993", "11", "MO-1", "M1", "2.5"),
                            row("9007199254740993", "12", "MO-1", "M2", "1.9"),
                            row("9007199254740993", "13", "MO-1", "M3", "0.6"));
            data(b.post("/orders/sync", Map.of()));
            var context = data(b.get("/generation/context?billId=" + bill));
            assertThat(context.path("bill").path("totalQty").asString()).isEqualTo("3");
            assertThat(context.path("bill").path("quota").asString()).isEqualTo("3");
            run(generate(b, preview(b, bill, "3")));
            assertThat(count("sn_item")).isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE material='M1'", Long.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE material='M2'", Long.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE material='M3'", Long.class))
                    .isZero();
            assertThat(data(b.get("/orders")).path("items").get(0).path("quota").asString())
                    .isEqualTo("0");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT qty FROM sn_order_entry WHERE source_entry_id='11'",
                                    java.math.BigDecimal.class))
                    .isEqualByComparingTo("2.5");
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
            var locked = b.post("/pi-initializations", init(uuid(), "HISTORY", null, List.of("X")));
            assertThat(locked.statusCode()).isEqualTo(409);
            assertThat(json.readTree(locked.body()).path("params").path("reason").asString())
                    .isEqualTo("PI_INIT_LOCKED");
            assertThat(json.readTree(locked.body()).path("params").path("pi").asString())
                    .isEqualTo("HISTORY");
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
    void initializationValidationReportsOriginalLineAndCapacityWithoutCreatingWork()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            data(b.post("/rules", rule("GENERAL", "GENERAL", "", "G")));
            var invalid =
                    b.post(
                            "/pi-initializations",
                            init(uuid(), "LIMIT", "10", List.of("", "VALID", "L".repeat(65))));
            assertThat(invalid.statusCode()).isEqualTo(400);
            var params = json.readTree(invalid.body()).path("params");
            assertThat(params.path("reason").asString()).isEqualTo("PI_IMPORT_SN_INVALID");
            assertThat(params.path("line").asString()).isEqualTo("3");
            var capacity = b.post("/pi-initializations", init(uuid(), "LIMIT", "10000", List.of()));
            assertThat(capacity.statusCode()).isEqualTo(409);
            params = json.readTree(capacity.body()).path("params");
            assertThat(params.path("reason").asString()).isEqualTo("GEN_START_INVALID");
            assertThat(params.path("max").asString()).isEqualTo("9999");
            var empty = b.post("/pi-initializations", init(uuid(), "LIMIT", null, List.of("  ")));
            assertThat(json.readTree(empty.body()).path("params").path("reason").asString())
                    .isEqualTo("PI_INIT_EMPTY");
            var duplicate =
                    b.post(
                            "/pi-initializations",
                            init(uuid(), "LIMIT", null, List.of(" S-1 ", "S-1")));
            assertThat(duplicate.statusCode()).isEqualTo(409);
            params = json.readTree(duplicate.body()).path("params");
            assertThat(params.path("reason").asString()).isEqualTo("PI_IMPORT_DUP_IN_LIST");
            assertThat(params.path("sn").asString()).isEqualTo("S-1");
            for (String table :
                    List.of(
                            "sn_work",
                            "sys_task",
                            "sys_operation",
                            "sn_item",
                            "sn_key",
                            "sn_pi_counter")) assertThat(count(table)).as(table).isZero();
            String accepted =
                    data(b.post(
                                    "/pi-initializations",
                                    init(uuid(), "LIMIT", "10", List.of("😀".repeat(64)))))
                            .path("taskId")
                            .asString();
            run(accepted);
            assertThat(count("sn_item")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(sn) FROM sn_item", Integer.class))
                    .isEqualTo(64);
            assertThat(last("LIMIT")).isEqualTo(9);
        }
    }

    @Test
    void initializationFailurePersistsOnlyPublicParametersAndAllowsFreshSubmission()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            var command = init(uuid(), "FAILURE", "10", List.of("UNKNOWN"));
            String task = data(b.post("/pi-initializations", command)).path("taskId").asString();
            var lease = worker.claimSubmitted(task, uuid());
            worker.failed(
                    lease,
                    new com.adtek.common.core.BusinessException(
                            com.adtek.sn.constant.ErrorCodeConstants.PI_INIT_LOCKED,
                            Map.of(
                                    "reason",
                                    "PI_INIT_LOCKED",
                                    "pi",
                                    "FAILURE",
                                    "secret",
                                    "must-not-persist")));
            var failed = data(b.get("/pi-initializations/history?pi=FAILURE")).path("items").get(0);
            assertThat(failed.path("state").asString()).isEqualTo("FAILED");
            assertThat(failed.path("errorParams").path("reason").asString())
                    .isEqualTo("PI_INIT_LOCKED");
            assertThat(failed.path("errorParams").path("pi").asString()).isEqualTo("FAILURE");
            assertThat(failed.path("errorParams").has("secret")).isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT error_params FROM sn_work WHERE task_id=?",
                                    String.class,
                                    task))
                    .doesNotContain("must-not-persist");
            assertThat(count("sn_item")).isZero();
            assertThat(last("FAILURE")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT active_task_id FROM sn_pi_counter WHERE pi='FAILURE'",
                                    String.class))
                    .isNull();
            assertThat(data(b.post("/pi-initializations", command)).path("taskId").asString())
                    .isEqualTo(task);
            run(
                    data(b.post(
                                    "/pi-initializations",
                                    init(uuid(), "FAILURE", "10", List.of("UNKNOWN"))))
                            .path("taskId")
                            .asString());
            assertThat(count("sn_item")).isEqualTo(1);
            assertThat(last("FAILURE")).isEqualTo(9);
        }
    }

    @Test
    void initializationExistingSnInSecondChunkRollsBackAndReportsConflict() throws Exception {
        initializationLedgerConflict(false);
    }

    @Test
    void stagedInputPreservesLegacyReplayAndRejectsMissingOrTamperedChunks() throws Exception {
        try (var b = new Browser("sn_hq")) {
            var sns =
                    java.util.stream.IntStream.range(0, 1001).mapToObj(i -> "CHUNK-" + i).toList();
            for (String fault : List.of("gap", "tamper", "legacy")) {
                var command = init(uuid(), "CHUNK-" + fault, "10", sns);
                String task =
                        data(b.post("/pi-initializations", command)).path("taskId").asString();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sn_import_chunk WHERE task_id=?",
                                        Integer.class,
                                        task))
                        .isEqualTo(2);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT JSON_LENGTH(payload,'$.sns') FROM sn_work WHERE task_id=?",
                                        Integer.class,
                                        task))
                        .isZero();
                assertThat(data(b.post("/pi-initializations", command)).path("taskId").asString())
                        .isEqualTo(task);
                if (fault.equals("legacy")) {
                    jdbc.update(
                            "UPDATE sn_work SET payload=JSON_SET(payload,'$.sns',CAST(? AS JSON)) WHERE task_id=?",
                            json.writeValueAsString(sns),
                            task);
                    jdbc.update("DELETE FROM sn_import_chunk WHERE task_id=?", task);
                    run(task);
                    assertThat(last("CHUNK-legacy")).isEqualTo(9);
                } else {
                    if (fault.equals("gap"))
                        jdbc.update(
                                "DELETE FROM sn_import_chunk WHERE task_id=? AND ordinal=1", task);
                    else
                        jdbc.update(
                                "UPDATE sn_import_chunk SET sns=JSON_ARRAY('TAMPERED') WHERE task_id=? AND ordinal=1",
                                task);
                    var lease = worker.claim(uuid());
                    assertThat(lease.id()).isEqualTo(task);
                    assertThatThrownBy(() -> worker.execute(lease))
                            .isInstanceOf(IllegalStateException.class);
                    // 输入破坏是本用例注入；在真实失败回调前复核业务事务没有残留。
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sn_item WHERE pi=?",
                                            Long.class,
                                            "CHUNK-" + fault))
                            .isZero();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sn_key WHERE pi=?",
                                            Long.class,
                                            "CHUNK-" + fault))
                            .isZero();
                    worker.failed(
                            lease,
                            new com.adtek.common.core.BusinessException(
                                    com.adtek.sn.constant.ErrorCodeConstants.SN_CONFLICT));
                }
            }
        }
    }

    @Test
    void importLimitIsIndependentFromGenerationAndRejectsBeforeTaskRegistration() throws Exception {
        int old = workSettings.getMaxImportQuantity(),
                generation = workSettings.getMaxBatchQuantity();
        try (var b = new Browser("sn_hq")) {
            workSettings.setMaxImportQuantity(1001);
            workSettings.setMaxBatchQuantity(1);
            var sns =
                    java.util.stream.IntStream.range(0, 1002).mapToObj(i -> "LIMIT-" + i).toList();
            var response = b.post("/pi-initializations", init(uuid(), "LIMIT-IMPORT", "1", sns));
            var error = json.readTree(response.body());
            assertThat(error.path("code").asInt()).isEqualTo(1004000011);
            assertThat(error.path("params").path("reason").asString())
                    .isEqualTo("PI_IMPORT_TOO_LARGE");
            assertThat(error.path("params").path("max").asString()).isEqualTo("1001");
            assertThat(count("sn_work")).isZero();
            assertThat(count("sn_import_chunk")).isZero();
            assertThat(count("sys_task")).isZero();
            run(
                    data(b.post(
                                    "/pi-initializations",
                                    init(uuid(), "LIMIT-IMPORT", "1", sns.subList(0, 1001))))
                            .path("taskId")
                            .asString());
            assertThat(count("sn_item")).isEqualTo(1001);
            assertThat(count("sn_import_chunk")).isEqualTo(2);
        } finally {
            workSettings.setMaxImportQuantity(old);
            workSettings.setMaxBatchQuantity(generation);
        }
    }

    @Test
    void initializationExistingSequenceInSecondChunkRollsBackAndReportsConflict() throws Exception {
        initializationLedgerConflict(true);
    }

    /** 固定历史查重键，不创建业务成功响应；实际工作器在第二块查重时失败。 */
    void initializationLedgerConflict(boolean sequence) throws Exception {
        try (var b = new Browser("sn_hq")) {
            data(b.post("/rules", rule("IMPORT", "PI", "IMPORT-PI", "G")));
            jdbc.update(
                    "INSERT INTO sn_key(id,gen_month,pi,sn,seq_pi,seq_dec) VALUES(?,CURRENT_DATE,?,?,?,?)",
                    uuid(),
                    sequence ? "ARCHIVED-PI" : "IMPORT-PI",
                    "EXISTING",
                    sequence ? "IMPORT-PI" : null,
                    sequence ? 5L : null);
            var sns =
                    new ArrayList<>(
                            java.util.stream.IntStream.range(0, 1000)
                                    .mapToObj(i -> "RAW-" + i)
                                    .toList());
            sns.add(sequence ? "G0005" : "EXISTING");
            String operation = uuid();
            var command = init(operation, "IMPORT-PI", "10", sns);
            String task = data(b.post("/pi-initializations", command)).path("taskId").asString();
            assertThat(fail(task)).isEqualTo(1004000015);
            assertThat(count("sn_item")).isZero();
            assertThat(count("sn_key")).isEqualTo(1);
            assertThat(last("IMPORT-PI")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT imported_qty FROM sn_pi_counter WHERE pi='IMPORT-PI'",
                                    Long.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT start_locked FROM sn_pi_counter WHERE pi='IMPORT-PI'",
                                    Boolean.class))
                    .isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT active_task_id FROM sn_pi_counter WHERE pi='IMPORT-PI'",
                                    String.class))
                    .isNull();
            assertThat(jdbc.queryForObject("SELECT used FROM sn_rule_version", Boolean.class))
                    .isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT processed FROM sys_task_progress WHERE task_id=?",
                                    Long.class,
                                    task))
                    .isEqualTo(1000);
            var result =
                    data(b.get("/pi-initializations/history?pi=IMPORT-PI")).path("items").get(0);
            assertThat(result.path("state").asString()).isEqualTo("FAILED");
            assertThat(result.path("errorParams").path("reason").asString())
                    .isEqualTo(sequence ? "PI_IMPORT_SEQ_DUP" : "PI_IMPORT_DUP_EXISTING");
            assertThat(result.path("errorParams").path("pi").asString()).isEqualTo("IMPORT-PI");
            assertThat(result.path("errorParams").path(sequence ? "seq" : "sn").asString())
                    .isEqualTo(sequence ? "5" : "EXISTING");
            assertThat(data(b.post("/pi-initializations", command)).path("taskId").asString())
                    .isEqualTo(task);
            sns.set(1000, "G0008");
            run(
                    data(b.post("/pi-initializations", init(uuid(), "IMPORT-PI", "10", sns)))
                            .path("taskId")
                            .asString());
            assertThat(count("sn_item")).isEqualTo(1001);
            assertThat(count("sn_key")).isEqualTo(1002);
            assertThat(last("IMPORT-PI")).isEqualTo(9);
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
    void legacyFullOrderAllocationRejectsStaleCountAndReplayDoesNotAllocateTwice()
            throws Exception {
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
    void partialAllocationFixesEarliestMembersAndKeepsRemainingAvailable() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "13")));
            for (String qty : List.of("0", "-1", "1.5", "")) {
                assertThat(
                                b.post(
                                                "/allocations",
                                                Map.of(
                                                        "operationId",
                                                        uuid(),
                                                        "billId",
                                                        bill,
                                                        "expectedQuantity",
                                                        "13",
                                                        "quantity",
                                                        qty))
                                        .statusCode())
                        .isEqualTo(400);
            }
            assertThat(
                            b.post(
                                            "/allocations",
                                            Map.of(
                                                    "operationId",
                                                    uuid(),
                                                    "billId",
                                                    bill,
                                                    "expectedQuantity",
                                                    "13",
                                                    "quantity",
                                                    "14"))
                                    .body())
                    .contains("1004000029");
            var command =
                    Map.of(
                            "operationId",
                            uuid(),
                            "billId",
                            bill,
                            "expectedQuantity",
                            "13",
                            "quantity",
                            "3");
            String task = data(b.post("/allocations", command)).path("taskId").asString();
            run(task);
            assertThat(data(b.post("/allocations", command)).path("taskId").asString())
                    .isEqualTo(task);
            var context = data(b.get("/generation/context?billId=" + bill));
            var job = context.path("jobs").get(0);
            assertThat(job.path("actorName").asString()).isEqualTo("sn_hq");
            assertThat(job.path("generationNumber").asString()).matches("[1-9][0-9]*");
            assertThat(job.path("processed").asString()).isEqualTo("13");
            var allocation = context.path("allocations").get(0);
            assertThat(allocation.path("actorName").asString()).isEqualTo("sn_hq");
            assertThat(allocation.path("processed").asString()).isEqualTo("3");
            assertThat(allocation.path("generationNumber").isNull()).isTrue();
            var changed = new HashMap<>(command);
            changed.put("quantity", "4");
            assertThat(b.post("/allocations", changed).statusCode()).isEqualTo(409);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT seq_dec FROM sn_item WHERE allocation_id=? ORDER BY seq_dec",
                                    Long.class,
                                    task))
                    .containsExactly(1L, 2L, 3L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT quantity FROM sn_work WHERE task_id=?",
                                    Long.class,
                                    task))
                    .isEqualTo(3);
            assertThat(
                            data(b.get("/generation/context?billId=" + bill))
                                    .path("pendingQuantity")
                                    .asString())
                    .isEqualTo("10");
            assertThat(
                            b.post(
                                            "/allocations",
                                            Map.of(
                                                    "operationId",
                                                    uuid(),
                                                    "billId",
                                                    bill,
                                                    "expectedQuantity",
                                                    "13",
                                                    "quantity",
                                                    "3"))
                                    .statusCode())
                    .isEqualTo(409);
            String rest =
                    data(b.post(
                                    "/allocations",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "billId",
                                            bill,
                                            "expectedQuantity",
                                            "10",
                                            "quantity",
                                            "10")))
                            .path("taskId")
                            .asString();
            run(rest);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT seq_dec FROM sn_item WHERE allocation_id=? ORDER BY seq_dec",
                                    Long.class,
                                    rest))
                    .containsExactly(4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT allocated_qty FROM sn_source_entry_quota WHERE bill_id=? ORDER BY source_entry_id",
                                    Long.class,
                                    Long.parseLong(bill)))
                    .containsExactly(8L, 3L, 2L);
            assertThat(last("PI-1")).isEqualTo(13);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='TO_ACQUIRE' AND factory_id=source_factory_id",
                                    Long.class))
                    .isEqualTo(13);
        }
    }

    @Test
    void partialAllocationRejectsMemberReplacementWithoutExpandingToLaterRows() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "10")));
            String task =
                    data(b.post(
                                    "/allocations",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "billId",
                                            bill,
                                            "expectedQuantity",
                                            "10",
                                            "quantity",
                                            "3")))
                            .path("taskId")
                            .asString();
            // 模拟提交后某个固定成员已被其他路径改变；不得悄悄把第 4 枚补入。
            jdbc.update("UPDATE sn_item SET status='TO_ACQUIRE' WHERE seq_dec=2");
            assertThat(fail(task)).isEqualTo(1_004_000_019);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE allocation_id=?",
                                    Long.class,
                                    task))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='PENDING_ALLOC'",
                                    Long.class))
                    .isEqualTo(9);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(allocated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isZero();
        }
    }

    @Test
    void partialAllocationRollsBackAllMemberWritesWhenLedgerCannotBeUpdated() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b);
            run(generate(b, preview(b, bill, "13")));
            String task =
                    data(b.post(
                                    "/allocations",
                                    Map.of(
                                            "operationId",
                                            uuid(),
                                            "billId",
                                            bill,
                                            "expectedQuantity",
                                            "13",
                                            "quantity",
                                            "10")))
                            .path("taskId")
                            .asString();
            // 跨分录写入后的故障仍须回滚全部 SN 和已更新的前一条台账。
            jdbc.update("DELETE FROM sn_source_entry_quota WHERE source_entry_id='12'");
            var lease = worker.claim(uuid());
            assertThat(lease.id()).isEqualTo(task);
            assertThatThrownBy(() -> worker.execute(lease))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_item WHERE status='PENDING_ALLOC' AND allocation_id IS NULL",
                                    Long.class))
                    .isEqualTo(13);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(allocated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM sn_work WHERE task_id=?",
                                    String.class,
                                    task))
                    .isEqualTo("PENDING");
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
            assertThat(fail(generate(b, p))).isEqualTo(1004000016);
            assertThat(count("sn_work")).isEqualTo(1);
            assertThat(count("sys_operation")).isEqualTo(1);
            assertThat(count("sn_item")).isZero();
        }
    }

    @Test
    void identicalResyncKeepsPreviewValidBeforeSubmitAndBeforeWorkerExecution() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), p = preview(b, bill, "3");
            long oldVersion =
                    jdbc.queryForObject(
                            "SELECT snapshot_version FROM sn_order_bill WHERE id=?",
                            Long.class,
                            bill);
            data(b.post("/orders/sync", Map.of("billNo", "MO-1")));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT snapshot_version FROM sn_order_bill WHERE id=?",
                                    Long.class,
                                    bill))
                    .isGreaterThan(oldVersion);
            String task = generate(b, p);
            data(b.post("/orders/sync", Map.of()));
            run(task);
            assertThat(last("PI-1")).isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT sn FROM sn_item ORDER BY seq_dec", String.class))
                    .containsExactly("G0001", "G0002", "G0003");
            assertThat(
                            b.post("/generation", Map.of("operationId", uuid(), "previewId", p))
                                    .statusCode())
                    .isEqualTo(409);
        }
    }

    @Test
    void unchangedSegmentsUseCurrentCustomerAndEntryEvenWhenErpIdentityChangesTwice()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), p = preview(b, bill, "3");
            ERP.orders =
                    List.of(
                            K3ProtocolFixture.row(
                                    "9007199254740993",
                                    "99",
                                    "MO-1",
                                    "CUSTOMER-NEW",
                                    "PI-1",
                                    "101",
                                    "ERP-A",
                                    "甲工厂",
                                    "M1",
                                    "20.9"));
            data(b.post("/orders/sync", Map.of()));
            String task = generate(b, p);
            ERP.orders =
                    List.of(
                            K3ProtocolFixture.row(
                                    "9007199254740993",
                                    "100",
                                    "MO-1",
                                    "CUSTOMER-CURRENT",
                                    "PI-1",
                                    "101",
                                    "ERP-A",
                                    "甲工厂",
                                    "M1",
                                    "21.7"));
            data(b.post("/orders/sync", Map.of()));
            run(task);
            assertThat(jdbc.queryForList("SELECT DISTINCT customer FROM sn_item", String.class))
                    .containsExactly("CUSTOMER-CURRENT");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT customer FROM sn_audit WHERE action='sn:generation:execute' AND result='SUCCESS' AND pi='PI-1' AND operation_id=(SELECT o.operation_id FROM sys_operation o WHERE o.task_id=?)",
                                    String.class,
                                    task))
                    .containsExactly("CUSTOMER-CURRENT");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT DISTINCT source_entry_id FROM sn_item", String.class))
                    .containsExactly("100");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT generated_qty FROM sn_source_entry_quota WHERE bill_id=? AND source_entry_id='100'",
                                    Long.class,
                                    bill))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(generated_qty) FROM sn_source_entry_quota WHERE bill_id=? AND source_entry_id<>'100'",
                                    Long.class,
                                    bill))
                    .isZero();
            assertThat(
                            data(b.get("/generation/context?billId=" + bill))
                                    .path("bill")
                                    .path("quota")
                                    .asString())
                    .isEqualTo("18");
        }
    }

    @Test
    void materialNameChangeCreatesFailedHistoryWhetherChangedBeforeOrAfterSubmission()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), p = preview(b, bill, "3");
            var changed = new ArrayList<>(row("9007199254740993", "11", "MO-1", "M1", "8"));
            changed.set(5, "新物料名称");
            ERP.orders = List.of(changed);
            data(b.post("/orders/sync", Map.of()));
            assertThat(fail(generate(b, p))).isEqualTo(1004000016);
            assertThat(count("sn_work")).isEqualTo(1);
            String task = generate(b, preview(b, bill, "3"));
            sample();
            data(b.post("/orders/sync", Map.of()));
            assertThat(fail(task)).isEqualTo(1004000016);
            assertThat(count("sn_item")).isZero();
            assertThat(last("PI-1")).isZero();
        }
    }

    @Test
    void queuedWorkRechecksSnapshotAndNeverReleasesConsumedPreview() throws Exception {
        try (var b = new Browser("sn_hq")) {
            String bill = prepared(b), p = preview(b, bill, "3"), id = generate(b, p);
            ERP.orders = List.of(row("9007199254740993", "11", "MO-1", "CHANGED", "13"));
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
            ERP.orders = List.of(row("9007199254740993", "11", "MO-1", "M1", "800"));
            data(b.post("/orders/sync", Map.of()));
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
    void decreasedQuantityExhaustsQuotaAndOrgChangeCannotRedirectAllocation() throws Exception {
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
            assertThat(context.path("issues").toString())
                    .contains("QUOTA_EMPTY")
                    .doesNotContain("ALLOCATION_SOURCE_CHANGED")
                    .doesNotContain("QUOTA_ANOMALY");
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
            ERP.orders = List.of(row("9007199254740993", "11", "MO-1", "M1", "10001"));
            data(b.post("/orders/sync", Map.of()));
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
