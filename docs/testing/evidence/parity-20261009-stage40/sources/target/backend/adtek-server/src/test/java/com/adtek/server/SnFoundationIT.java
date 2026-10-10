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

/** 旧Factories/Orders/Rules行为在真实MySQL/Redis和HTTP协议夹具上的迁移回归，不连接真实ERP。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SnFoundationIT {
    static final String SECRET = HexFormat.of().formatHex(new SecureRandom().generateSeed(32)),
            PASSWORD = "SnStage9!" + SECRET.substring(0, 24);
    static final K3ProtocolFixture ERP = new K3ProtocolFixture();

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_sn_stage3_it")
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

    @AfterAll
    static void close() {
        ERP.close();
    }

    @BeforeEach
    void reset() {
        ERP.reset();
        jdbc.update(
                "UPDATE sn_order_sync_schedule SET enabled=FALSE,interval_minutes=1440,next_run_at=NULL,owner_id=NULL,last_run_id=NULL,last_run_at=NULL,lease_until=NULL,last_state=NULL,last_error_code=NULL,last_bills=NULL,last_rows=NULL,updated_at=NULL");
        for (String table :
                List.of(
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
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
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

    @Test
    void ruleDirectoryKeepsPrototypeBinaryOrderCaseAndWildcardSearch() throws Exception {
        try (var b = new Browser("sn_hq")) {
            data(b.post("/rules", rule("CMP_R", "PI", "CMP_UI", "B")));
            data(b.post("/rules", rule("cmp_r", "PI", "cmp_ui", "C")));
            data(b.post("/rules", rule("CMP-R", "PI", "CMP-PI", "A")));
            var first = data(b.get("/rules?size=2")).path("items");
            assertThat(first.get(0).path("code").asString()).isEqualTo("CMP-R");
            assertThat(first.get(1).path("code").asString()).isEqualTo("CMP_R");
            assertThat(
                            data(b.get("/rules?size=2&page=2"))
                                    .path("items")
                                    .get(0)
                                    .path("code")
                                    .asString())
                    .isEqualTo("cmp_r");
            assertThat(data(b.get("/rules?q=CMP_R")).path("total").asString()).isEqualTo("2");
            assertThat(data(b.get("/rules?q=cmp")).path("total").asString()).isEqualTo("1");
            assertThat(data(b.get("/rules?q=CMP%25")).path("total").asString()).isEqualTo("2");
            assertThat(
                            data(b.get("/rules/effective?pi=cmp_ui&customer=x"))
                                    .path("prefix")
                                    .asString())
                    .isEqualTo("C");
        }
    }

    @Test
    void manualFactoryConflictRequiresExplicitStableMappingAndNamesDoNotIdentifyOrganizations()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            var manual = data(b.post("/factories", Map.of("code", "MANUAL-A", "name", "甲工厂")));
            String id = manual.path("id").asString();
            assertThat(id).isEqualTo("9007199254742001");
            assertThat(b.post("/factories", Map.of("code", "MANUAL-B", "name", "甲工厂")).statusCode())
                    .isEqualTo(409);
            var sync = data(b.post("/factories/sync", Map.of()));
            assertThat(sync.path("conflicts").size()).isEqualTo(1);
            sample();
            data(b.post("/orders/sync", Map.of()));
            assertThat(data(b.get("/orders/meta")).path("unmappedOrganizations").size())
                    .isEqualTo(1);
            data(
                    b.post(
                            "/factories/" + id + "/organization",
                            Map.of("orgId", "101", "version", "1")));
            assertThat(data(b.get("/orders/meta")).path("unmappedOrganizations").size()).isZero();
            assertThat(
                            data(b.get("/orders?q=MO-1"))
                                    .path("items")
                                    .get(0)
                                    .path("factoryId")
                                    .asString())
                    .isEqualTo(id);
            assertThat(
                            b.post(
                                            "/factories/" + id + "/organization",
                                            Map.of("orgId", "102", "version", "2"))
                                    .statusCode())
                    .isEqualTo(409);
        }
    }

    @Test
    void completePagingPreservesOldOrderColumnsEmptyMaterialSameMaterialAndExactIds()
            throws Exception {
        sample();
        var rows = new ArrayList<>(ERP.orders);
        rows.add(
                K3ProtocolFixture.row(
                        "3", "31", "EMPTY-CUSTOMER", "", "PI-3", "101", "ERP-A", "甲工厂", "", "1"));
        ERP.orders = rows;
        try (var b = new Browser("sn_hq")) {
            var sync = data(b.post("/orders/sync", Map.of()));
            assertThat(sync.path("rowsCount").asString()).isEqualTo("5");
            var page = data(b.get("/orders?q=MO-1"));
            var bill = page.path("items").get(0);
            assertThat(bill.path("sourceBillId").asString()).isEqualTo("9007199254740993");
            assertThat(bill.path("totalQty").asString()).isEqualTo("13");
            assertThat(bill.path("lineCount").asString()).isEqualTo("3");
            var lines = data(b.get("/orders/" + bill.path("id").asString() + "/lines?size=2"));
            assertThat(lines.path("total").asString()).isEqualTo("3");
            assertThat(lines.path("items").size()).isEqualTo(2);
            var tail =
                    data(b.get("/orders/" + bill.path("id").asString() + "/lines?size=2&page=2"));
            assertThat(tail.path("items").get(0).path("material").asString()).isEmpty();
            var meta = data(b.get("/orders/meta"));
            assertThat(meta.path("bills").asString()).isEqualTo("2");
            assertThat(meta.path("excludedEmptyCustomer").asString()).isEqualTo("1");
            assertThat(data(b.get("/orders?q=M1")).path("items").get(0).path("totalQty").asString())
                    .isEqualTo("11");
        }
    }

    @Test
    void stableEntryLedgerSurvivesReorderRemovalAndEmptySnapshotWithoutMovingQuota()
            throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            data(b.post("/orders/sync", Map.of()));
            long bill =
                    jdbc.queryForObject(
                            "SELECT id FROM sn_order_bill WHERE bill_no='MO-1'", Long.class);
            jdbc.update(
                    "UPDATE sn_source_entry_quota SET generated_qty=5,allocated_qty=2 WHERE"
                            + " bill_id=? AND source_entry_id='11'",
                    bill);
            ERP.orders =
                    List.of(
                            row("9007199254740993", "14", "MO-1", "M1", "7"),
                            row("9007199254740993", "11", "MO-1", "M1", "2"));
            data(b.post("/orders/sync", Map.of("billNo", "MO-1")));
            var current = data(b.get("/orders?q=MO-1")).path("items").get(0);
            assertThat(current.path("generatedQty").asString()).isEqualTo("5");
            // 原型的订单/PI 额度按整单历史累计；稳定分录账本仍单独保留。
            assertThat(current.path("quota").asString()).isEqualTo("4");
            var pi = data(b.get("/orders/pis?pending=true&customer=C1")).path("items").get(0);
            assertThat(pi.path("quota").asString()).isEqualTo("4");
            assertThat(pi.path("generatedQty").asString()).isEqualTo("5");
            assertThat(pi.path("pendingQuantity").asString()).isEqualTo("3");
            var lines = data(b.get("/orders/" + bill + "/lines")).path("items");
            assertThat(lines.get(0).path("sourceEntryId").asString()).isEqualTo("14");
            assertThat(lines.get(0).path("quota").asString()).isEqualTo("7");
            assertThat(lines.get(1).path("sourceEntryId").asString()).isEqualTo("11");
            assertThat(lines.get(1).path("quota").asString()).isEqualTo("0");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT generated_qty FROM sn_source_entry_quota WHERE bill_id=? AND source_entry_id='11'",
                                    Long.class,
                                    bill))
                    .isEqualTo(5);
            ERP.orders = List.of();
            data(b.post("/orders/sync", Map.of()));
            assertThat(data(b.get("/orders")).path("total").asString()).isEqualTo("0");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT SUM(generated_qty) FROM sn_source_entry_quota",
                                    Long.class))
                    .isEqualTo(5L);
            assertThat(data(b.get("/orders/meta")).path("syncedAt").isNull()).isTrue();
        }
    }

    @Test
    void factoryScopeProtectsOrdersWhileSyncMetadataIsSharedAndExplicitFilterCannotExpandIt()
            throws Exception {
        sample();
        try (var hq = new Browser("sn_hq")) {
            data(hq.post("/orders/sync", Map.of()));
        }
        long own = jdbc.queryForObject("SELECT id FROM sn_factory WHERE code='ERP-A'", Long.class),
                other =
                        jdbc.queryForObject(
                                "SELECT id FROM sn_order_bill WHERE bill_no='MO-2'", Long.class);
        user(10, "bound_query", 5);
        jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(10,?)", own);
        user(11, "empty_query", 5);
        try (var b = new Browser("bound_query");
                var empty = new Browser("empty_query")) {
            assertThat(data(b.get("/orders")).path("total").asString()).isEqualTo("1");
            assertThat(b.get("/orders/" + other + "/lines").statusCode()).isEqualTo(404);
            assertThat(data(b.get("/orders/customers")).path("items").toString())
                    .isEqualTo("[\"C1\"]");
            assertThat(data(b.get("/orders/meta")).path("bills").asString()).isEqualTo("2");
            assertThat(data(empty.get("/orders/meta")).path("bills").asString()).isEqualTo("2");
            assertThat(
                            data(b.get("/orders/pis?pending=true"))
                                    .path("items")
                                    .get(0)
                                    .path("pi")
                                    .asString())
                    .isEqualTo("PI-1");
            assertThat(data(b.get("/orders/pis?customer=C2")).path("total").asString())
                    .isEqualTo("0");
            assertThat(data(b.get("/orders?pending=true&customer=C2")).path("total").asString())
                    .isEqualTo("0");
            assertThat(data(b.get("/orders/pis?factoryId=1")).path("total").asString())
                    .isEqualTo("0");
            assertThat(data(empty.get("/orders/pis")).path("total").asString()).isEqualTo("0");
            assertThat(data(b.get("/orders?factoryId=1")).path("total").asString()).isEqualTo("0");
            assertThat(b.post("/orders/sync", Map.of()).statusCode()).isEqualTo(403);
            assertThat(b.get("/orders/syncs").statusCode()).isEqualTo(403);
            assertThat(data(empty.get("/orders")).path("total").asString()).isEqualTo("0");
            assertThat(data(empty.get("/orders/customers")).path("total").asString())
                    .isEqualTo("0");
        }
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope,factory_scope)"
                        + " VALUES(1000,'BOUND_SYNC','限定工厂同步',1,1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1000,221)");
        user(13, "read_all_write_bound", 4);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(13,1000)");
        jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(13,?)", own);
        try (var mixed = new Browser("read_all_write_bound")) {
            assertThat(data(mixed.get("/orders")).path("total").asString()).isEqualTo("2");
            assertThat(mixed.post("/orders/sync", Map.of()).statusCode()).isEqualTo(403);
        }
    }

    @Test
    void laterPageFailureAndInvalidStableIdentityOrExcessPrecisionKeepPreviousVersion()
            throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            data(b.post("/orders/sync", Map.of()));
            long version =
                    jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class);
            ERP.failure = "page";
            assertThat(b.post("/orders/sync", Map.of()).statusCode()).isGreaterThanOrEqualTo(400);
            ERP.failure = "";
            ERP.orders = List.of(row("9007199254740993", "11", "MO-1", "M1", "1.00001"));
            assertThat(b.post("/orders/sync", Map.of()).statusCode()).isEqualTo(502);
            ERP.orders = List.of(row("9007199254740993", "", "MO-1", "M1", "1"));
            assertThat(b.post("/orders/sync", Map.of()).statusCode()).isEqualTo(502);
            assertThat(jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class))
                    .isEqualTo(version);
            assertThat(data(b.get("/orders")).path("total").asString()).isEqualTo("2");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_order_sync WHERE state='FAILED'",
                                    Integer.class))
                    .isEqualTo(3);
        }
    }

    @Test
    void singleBillNotFoundIsLegalEmptyButOtherViewFailuresPreserveSnapshot() throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            data(b.post("/orders/sync", Map.of()));
            ERP.failure = "view";
            assertThat(b.post("/orders/sync", Map.of("billNo", "MO-1")).statusCode())
                    .isEqualTo(502);
            assertThat(data(b.get("/orders?q=MO-1")).path("total").asString()).isEqualTo("1");
            ERP.failure = "";
            ERP.orders = ERP.orders.stream().filter(r -> !r.get(1).equals("MO-1")).toList();
            var result = data(b.post("/orders/sync", Map.of("billNo", "MO-1")));
            assertThat(result.path("rowsCount").asString()).isEqualTo("0");
            assertThat(data(b.get("/orders")).path("total").asString()).isEqualTo("1");
        }
    }

    @Test
    void twoConcurrentFetchesCannotPublishOlderSnapshotOverNewerVersion() throws Exception {
        sample();
        ERP.barrier = new CountDownLatch(2);
        try (var b = new Browser("sn_hq");
                var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> b.post("/orders/sync", Map.of()));
            var two = pool.submit(() -> b.post("/orders/sync", Map.of()));
            assertThat(
                            List.of(
                                    one.get(30, TimeUnit.SECONDS).statusCode(),
                                    two.get(30, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_order_sync WHERE state='SUCCESS'",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void rulePriorityRenameUnusedEditUsedNewVersionAndHistoryArePreserved() throws Exception {
        try (var b = new Browser("sn_hq")) {
            data(b.post("/rules", rule("GENERAL", "GENERAL", "", "G")));
            data(b.post("/rules", rule("CUSTOMER", "CUSTOMER", "C1", "C")));
            var pi = rule("PI_RULE", "PI", "PI-1", "A");
            var created = data(b.post("/rules", pi));
            assertThat(created.path("current").path("createdByName").asString()).isEqualTo("sn_hq");
            String id = created.path("id").asString();
            assertThat(
                            data(b.get("/rules/effective?pi=PI-1&customer=C1"))
                                    .path("prefix")
                                    .asString())
                    .isEqualTo("A");
            assertThat(
                            data(b.get("/rules/effective?pi=PI-2&customer=C1"))
                                    .path("prefix")
                                    .asString())
                    .isEqualTo("C");
            assertThat(
                            data(b.get("/rules/effective?pi=PI-2&customer=C2"))
                                    .path("prefix")
                                    .asString())
                    .isEqualTo("G");
            pi.put("revision", "1");
            pi.put("name", "改名");
            assertThat(data(b.post("/rules/" + id + "/update", pi)).path("action").asString())
                    .isEqualTo("RENAMED");
            pi.put("revision", "2");
            pi.put("prefix", "B");
            var updated = data(b.post("/rules/" + id + "/update", pi));
            assertThat(updated.path("action").asString()).isEqualTo("UPDATED");
            String digest = updated.path("rule").path("current").path("contentDigest").asString();
            // S4生成尚未迁移；直接在隔离数据库模拟已被历史SN引用，不冒充生成闭环。
            jdbc.update("UPDATE sn_rule_version SET used=TRUE WHERE rule_id=?", Long.parseLong(id));
            pi.put("revision", "3");
            pi.put("prefix", "C");
            assertThat(data(b.post("/rules/" + id + "/update", pi)).path("action").asString())
                    .isEqualTo("NEW_VERSION");
            var history = data(b.get("/rules/" + id + "/versions")).path("items");
            assertThat(history.size()).isEqualTo(2);
            assertThat(history.get(1).path("prefix").asString()).isEqualTo("B");
            assertThat(history.get(1).path("contentDigest").asString()).isEqualTo(digest);
            var duplicate = b.post("/rules", rule("DUP", "PI", "PI-1", "D"));
            assertThat(duplicate.statusCode()).isEqualTo(409);
            var error = json.readTree(duplicate.body());
            assertThat(error.path("params").path("reason").asString())
                    .isEqualTo("RULE_BIND_EXISTS");
            assertThat(error.path("params").path("rule_code").asString()).isEqualTo("PI_RULE");
            var sameCode =
                    json.readTree(b.post("/rules", rule("PI_RULE", "PI", "OTHER", "D")).body());
            assertThat(sameCode.path("params").path("reason").asString())
                    .isEqualTo("RULE_CODE_EXISTS");
            jdbc.update(
                    "UPDATE sys_user SET display_name='renamed display' WHERE username='sn_hq'");
            assertThat(
                            data(b.get("/rules/" + id + "/versions"))
                                    .path("items")
                                    .get(1)
                                    .path("createdByName")
                                    .asString())
                    .isEqualTo("sn_hq");
        }
    }

    @Test
    void ruleCapacityPrecisionValidationAndStaleConcurrentEdits() throws Exception {
        try (var b = new Browser("sn_hq")) {
            var preview =
                    data(
                            b.post(
                                    "/rules/preview",
                                    Map.of(
                                            "prefix",
                                            "😀",
                                            "suffix",
                                            "",
                                            "base",
                                            36,
                                            "length",
                                            12,
                                            "alphabet",
                                            "",
                                            "start",
                                            "9007199254740993")));
            assertThat(preview.path("maxSequence").asString()).isEqualTo("4738381338321616895");
            assertThat(preview.path("samples").get(0).path("seqDecimal").asString())
                    .isEqualTo("9007199254740993");
            assertThat(b.post("/rules/preview", Map.of("base", 8, "length", 4)).statusCode())
                    .isEqualTo(400);
            var command = rule("CONCURRENT", "PI", "P", "A");
            String id = data(b.post("/rules", command)).path("id").asString();
            command.put("revision", "1");
            command.put("prefix", "B");
            try (var pool = Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> b.post("/rules/" + id + "/update", command));
                var two = pool.submit(() -> b.post("/rules/" + id + "/update", command));
                assertThat(List.of(one.get().statusCode(), two.get().statusCode()))
                        .containsExactlyInAnyOrder(200, 409);
            }
        }
    }

    @Test
    void successfulAuditFailureRollsBackFactoryRuleAndEntireOrderPublication() throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_it_reject_sn_success CHECK(action"
                            + " NOT LIKE 'sn:%' OR success=FALSE)");
            try {
                assertThat(
                                b.post("/factories", Map.of("code", "ROLLBACK", "name", "回滚工厂"))
                                        .statusCode())
                        .isEqualTo(500);
                assertThat(b.post("/rules", rule("ROLLBACK", "PI", "P", "X")).statusCode())
                        .isEqualTo(500);
                assertThat(b.post("/orders/sync", Map.of()).statusCode()).isEqualTo(500);
                for (String table :
                        List.of(
                                "sn_factory",
                                "sn_rule",
                                "sn_rule_version",
                                "sn_order_bill",
                                "sn_order_entry",
                                "sn_source_entry_quota"))
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                            .isZero();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT revision FROM sn_snapshot_control", Long.class))
                        .isZero();
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_it_reject_sn_success");
            }
        }
    }

    @Test
    void erpLoginFailureDoesNotLeakResponseAndOrganizationFailureDoesNotEraseOrders()
            throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            ERP.failure = "login";
            var rejected = b.post("/orders/sync", Map.of());
            assertThat(rejected.statusCode()).isEqualTo(502);
            assertThat(json.readTree(rejected.body()).path("code").asInt()).isEqualTo(1005000002);
            assertThat(rejected.body()).doesNotContain("SYNTHETIC_SECRET", SECRET, PASSWORD);
            ERP.failure = "org";
            var result = data(b.post("/orders/sync", Map.of()));
            assertThat(result.path("orgSyncFailed").asBoolean()).isTrue();
            assertThat(data(b.get("/orders/meta")).path("unmappedOrganizations").size())
                    .isEqualTo(2);
        }
    }

    @Test
    void sourceOrganizationChangeToUnmappedClearsPreviousFactoryScope() throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            data(b.post("/orders/sync", Map.of()));
            long old =
                    jdbc.queryForObject("SELECT id FROM sn_factory WHERE code='ERP-A'", Long.class);
            user(10, "old_factory", 5);
            jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(10,?)", old);
            ERP.orders =
                    List.of(
                            K3ProtocolFixture.row(
                                    "9007199254740993",
                                    "11",
                                    "MO-1",
                                    "C1",
                                    "PI-1",
                                    "999",
                                    "UNKNOWN",
                                    "未映射厂",
                                    "M1",
                                    "8"));
            data(b.post("/orders/sync", Map.of("billNo", "MO-1")));
            var current = data(b.get("/orders?q=MO-1")).path("items").get(0);
            assertThat(current.path("factoryId").isNull()).isTrue();
            try (var previous = new Browser("old_factory")) {
                assertThat(data(previous.get("/orders")).path("total").asString()).isEqualTo("0");
                assertThat(
                                previous.get("/orders/" + current.path("id").asString() + "/lines")
                                        .statusCode())
                        .isEqualTo(404);
            }
        }
    }

    @Test
    void userFactoryAssignmentIsAtomicScopedAndRevokesPreviousSession() throws Exception {
        sample();
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(9007199254741001,1)");
        try (var hq = new Browser("sn_hq")) {
            data(hq.post("/orders/sync", Map.of()));
            String
                    a =
                            jdbc.queryForObject(
                                    "SELECT id FROM sn_factory WHERE code='ERP-A'", String.class),
                    other =
                            jdbc.queryForObject(
                                    "SELECT id FROM sn_factory WHERE code='ERP-B'", String.class);
            var created =
                    data(
                            hq.raw(
                                    "POST",
                                    "/system/users",
                                    Map.of(
                                            "username",
                                            "assigned",
                                            "displayName",
                                            "绑定用户",
                                            "deptId",
                                            "1",
                                            "roleIds",
                                            List.of("5"),
                                            "factoryIds",
                                            List.of(a),
                                            "password",
                                            PASSWORD),
                                    hq.token,
                                    hq.csrf));
            String id = created.path("id").asString();
            assertThat(created.path("factoryIds").toString()).isEqualTo("[\"" + a + "\"]");
            jdbc.update(
                    "UPDATE sys_user SET must_change_password=FALSE WHERE id=?",
                    Long.parseLong(id));
            try (var target = new Browser("assigned")) {
                assertThat(
                                data(target.get("/orders"))
                                        .path("items")
                                        .get(0)
                                        .path("billNo")
                                        .asString())
                        .isEqualTo("MO-1");
                var patch =
                        new HashMap<String, Object>(
                                Map.of(
                                        "displayName",
                                        "绑定用户",
                                        "deptId",
                                        "1",
                                        "roleIds",
                                        List.of("5"),
                                        "factoryIds",
                                        List.of(other),
                                        "version",
                                        "1"));
                data(hq.raw("POST", "/system/users/" + id + "/update", patch, hq.token, hq.csrf));
                assertThat(target.get("/orders").statusCode()).isEqualTo(401);
                patch.put("factoryIds", List.of("999"));
                patch.put("version", "2");
                assertThat(
                                hq.raw(
                                                "POST",
                                                "/system/users/" + id + "/update",
                                                patch,
                                                hq.token,
                                                hq.csrf)
                                        .statusCode())
                        .isEqualTo(400);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT security_version FROM sys_user WHERE id=?",
                                        Long.class,
                                        Long.parseLong(id)))
                        .isEqualTo(2);
            }
            try (var target = new Browser("assigned")) {
                assertThat(
                                data(target.get("/orders"))
                                        .path("items")
                                        .get(0)
                                        .path("billNo")
                                        .asString())
                        .isEqualTo("MO-2");
            }
            user(12, "platform_only", 1);
            try (var platform = new Browser("platform_only")) {
                assertThat(
                                platform.raw(
                                                "POST",
                                                "/system/users",
                                                Map.of(
                                                        "username",
                                                        "forbidden_binding",
                                                        "displayName",
                                                        "禁止绑定",
                                                        "deptId",
                                                        "1",
                                                        "roleIds",
                                                        List.of(),
                                                        "factoryIds",
                                                        List.of(a),
                                                        "password",
                                                        PASSWORD),
                                                platform.token,
                                                platform.csrf)
                                        .statusCode())
                        .isEqualTo(403);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user WHERE"
                                                + " username='forbidden_binding'",
                                        Integer.class))
                        .isZero();
            }
        }
    }

    @Test
    void effectiveRuleInfersCustomerFromCurrentPiAsOriginalPage() throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            data(b.post("/orders/sync", Map.of()));
            data(b.post("/rules", rule("G", "GENERAL", "", "G")));
            data(b.post("/rules", rule("C", "CUSTOMER", "C1", "C")));
            assertThat(data(b.get("/rules/effective?pi=PI-1")).path("prefix").asString())
                    .isEqualTo("C");
            assertThat(data(b.get("/rules/effective?pi=PI-2")).path("prefix").asString())
                    .isEqualTo("G");
        }
    }

    @Test
    void duplicateIdentityAndResourceLimitNeverPublishPartialOrders() throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            data(b.post("/orders/sync", Map.of()));
            long revision =
                    jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class);
            ERP.orders = List.of(row("1", "11", "DUP", "M", "1"), row("1", "11", "DUP", "M", "1"));
            assertThat(b.post("/orders/sync", Map.of()).statusCode()).isEqualTo(502);
            var rows = new ArrayList<List<Object>>();
            for (int i = 1; i <= 19; i++)
                rows.add(row("1", Integer.toString(i), "LIMIT", "M", "1"));
            ERP.orders = rows;
            assertThat(b.post("/orders/sync", Map.of()).statusCode()).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class))
                    .isEqualTo(revision);
            assertThat(data(b.get("/orders")).path("total").asString()).isEqualTo("2");
        }
    }

    @Test
    void auditStorageFailureDoesNotUndoKnownSyncFailureState() throws Exception {
        try (var b = new Browser("sn_hq")) {
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_it_reject_all_failure"
                            + " CHECK(success=TRUE)");
            try {
                ERP.failure = "login";
                assertThat(b.post("/orders/sync", Map.of()).statusCode()).isEqualTo(502);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sn_order_sync WHERE state='FAILED'",
                                        Integer.class))
                        .isEqualTo(1);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_it_reject_all_failure");
            }
        }
    }

    @Test
    void unknownLegacyRuleCreatorRemainsNullAcrossListEffectiveAndHistory() throws Exception {
        try (var b = new Browser("sn_hq")) {
            var created = data(b.post("/rules", rule("LEGACY_NULL", "GENERAL", "", "L")));
            String id = created.path("id").asString();
            assertThat(id).isNotEmpty();
            jdbc.update("UPDATE sn_rule SET created_by=NULL WHERE id=?", id);
            jdbc.update(
                    "UPDATE sn_rule_version SET created_by=NULL,created_by_name=NULL WHERE rule_id=?",
                    id);
            assertThat(
                            data(b.get("/rules"))
                                    .path("items")
                                    .get(0)
                                    .path("current")
                                    .path("createdBy")
                                    .isNull())
                    .isTrue();
            assertThat(data(b.get("/rules/effective?pi=LEGACY-PI")).path("createdBy").isNull())
                    .isTrue();
            assertThat(data(b.get("/rules/effective?pi=LEGACY-PI")).path("createdByName").isNull())
                    .isTrue();
            assertThat(
                            data(b.get("/rules/" + id + "/versions"))
                                    .path("items")
                                    .get(0)
                                    .path("createdBy")
                                    .isNull())
                    .isTrue();
        }
    }

    @Test
    void fractionalPlansAreStoredExactlyEvenWhenTheirSumIsAnInteger() throws Exception {
        ERP.orders =
                List.of(
                        row("91", "11", "FRACTION", "M", new java.math.BigDecimal("0.1254")),
                        row("91", "12", "FRACTION", "M", "0.8746"),
                        row("92", "21", "WHOLE", "M", "9007199254740993"));
        try (var b = new Browser("sn_hq")) {
            assertThat(data(b.post("/orders/sync", Map.of())).path("rowsCount").asString())
                    .isEqualTo("3");
            var bill = data(b.get("/orders?q=FRACTION")).path("items").get(0);
            assertThat(bill.path("totalQty").asString()).isEqualTo("0");
            assertThat(bill.path("quantityReady").asBoolean()).isTrue();
            assertThat(bill.path("quota").asString()).isEqualTo("0");
            var lines =
                    data(b.get("/orders/" + bill.path("id").asString() + "/lines")).path("items");
            assertThat(lines.get(0).path("qty").asString()).isEqualTo("0.1254");
            assertThat(lines.get(1).path("qty").asString()).isEqualTo("0.8746");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT qty FROM sn_order_entry WHERE source_entry_id='11'",
                                    java.math.BigDecimal.class))
                    .isEqualByComparingTo("0.1254");
            var whole = data(b.get("/orders?q=WHOLE")).path("items").get(0);
            assertThat(whole.path("totalQty").asString()).isEqualTo("9007199254740993");
            assertThat(whole.path("quantityReady").asBoolean()).isTrue();
            assertThat(data(b.get("/orders?pending=true&q=FRACTION")).path("total").asString())
                    .isEqualTo("0");
            var pi = data(b.get("/orders/pis?pending=true")).path("items").get(0);
            assertThat(pi.path("bills").asString()).isEqualTo("2");
            assertThat(pi.path("totalQty").asString()).isEqualTo("9007199254740993");
            assertThat(pi.path("quota").asString()).isEqualTo("9007199254740993");
        }
    }

    @Test
    void pendingOrderAndPiPagesIncludeUnallocatedHistoryAndExcludeFinishedBills() throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            data(b.post("/orders/sync", Map.of()));
            assertThat(data(b.get("/orders?q=C1")).path("total").asString()).isEqualTo("1");
            assertThat(data(b.get("/orders/pis?q=C1")).path("total").asString()).isEqualTo("0");
            assertThat(data(b.get("/orders/pis?q=PI&size=1")).path("total").asString())
                    .isEqualTo("2");
            assertThat(
                            data(b.get("/orders/pis?size=1&page=2"))
                                    .path("items")
                                    .get(0)
                                    .path("pi")
                                    .asString())
                    .isEqualTo("PI-1");
            jdbc.update(
                    "UPDATE sn_source_entry_quota q JOIN sn_order_entry e ON e.bill_id=q.bill_id"
                            + " AND e.source_entry_id=q.source_entry_id SET q.generated_qty=e.qty");
            var pending = data(b.get("/orders?pending=true&size=1"));
            assertThat(pending.path("total").asString()).isEqualTo("2");
            assertThat(pending.path("items").get(0).path("quota").asString()).isEqualTo("0");
            assertThat(
                            data(b.get("/orders/pis?customer=C1&pending=true"))
                                    .path("items")
                                    .get(0)
                                    .path("pendingQuantity")
                                    .asString())
                    .isEqualTo("13");
            jdbc.update("UPDATE sn_source_entry_quota SET allocated_qty=generated_qty");
            assertThat(data(b.get("/orders?pending=true")).path("total").asString()).isEqualTo("0");
            assertThat(data(b.get("/orders/pis?pending=true")).path("total").asString())
                    .isEqualTo("0");
            assertThat(data(b.get("/orders/pis")).path("total").asString()).isEqualTo("2");
        }
    }

    @Test
    void piAggregationIncludesVisibleOrdersOnlyAndUsesWholeBillQuota() throws Exception {
        ERP.orders =
                List.of(
                        row("1", "11", "LATEST", "M", "2"),
                        row("1", "12", "LATEST", "M", "6"),
                        K3ProtocolFixture.row(
                                "2", "21", "OLDER", "C2", "PI-1", "102", "ERP-B", "乙工厂", "M", "9"));
        try (var b = new Browser("sn_hq")) {
            data(b.post("/orders/sync", Map.of()));
            jdbc.update(
                    "UPDATE sn_source_entry_quota SET generated_qty=5,allocated_qty=5"
                            + " WHERE source_entry_id='11'");
            var pi = data(b.get("/orders/pis?pending=true")).path("items").get(0);
            assertThat(pi.path("bills").asString()).isEqualTo("2");
            assertThat(pi.path("quota").asString()).isEqualTo("12");
            var own = data(b.get("/orders/pis?customer=C1")).path("items").get(0);
            assertThat(own.path("quota").asString()).isEqualTo("3");
            assertThat(own.path("totalQty").asString()).isEqualTo("8");
        }
        long factory =
                jdbc.queryForObject("SELECT id FROM sn_factory WHERE code='ERP-A'", Long.class);
        user(10, "scoped_pi", 5);
        jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES(10,?)", factory);
        try (var b = new Browser("scoped_pi")) {
            var pi = data(b.get("/orders/pis?pending=true")).path("items").get(0);
            assertThat(pi.path("bills").asString()).isEqualTo("1");
            assertThat(pi.path("quota").asString()).isEqualTo("3");
            assertThat(data(b.get("/orders?q=C2&pending=true")).path("total").asString())
                    .isEqualTo("0");
        }
    }

    @Test
    void materialSearchAggregatesMatchingLinesButDetailsStayComplete() throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            data(b.post("/orders/sync", Map.of()));
            var filtered = data(b.get("/orders?q=M1")).path("items").get(0);
            assertThat(filtered.path("totalQty").asString()).isEqualTo("11");
            assertThat(filtered.path("lineCount").asString()).isEqualTo("2");
            assertThat(
                            data(b.get("/orders/" + filtered.path("id").asString() + "/lines"))
                                    .path("items")
                                    .size())
                    .isEqualTo(3);
        }
    }

    @Autowired com.adtek.sn.service.OrderScheduleService schedules;
    @Autowired com.adtek.sn.service.impl.OrderScheduleControl scheduleControl;
    @Autowired com.adtek.sn.service.OrderService orderService;
    @Autowired com.adtek.framework.security.AuthorizationPort authorizations;

    void due() {
        jdbc.update(
                "UPDATE sn_order_sync_schedule SET next_run_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
    }

    JsonNode saveSchedule(Browser browser, boolean enabled, int minutes) throws Exception {
        return data(
                browser.raw(
                        "PUT",
                        "/sn/orders/sync-schedule",
                        Map.of("enabled", enabled, "intervalMinutes", minutes),
                        browser.token,
                        browser.csrf));
    }

    @Test
    void scheduleSettingsValidateScopeIntervalsAndPreserveUnchangedNextRun() throws Exception {
        try (var b = new Browser("sn_hq")) {
            assertThat(data(b.get("/orders/sync-schedule")).path("enabled").asBoolean()).isFalse();
            var saved = saveSchedule(b, true, 10);
            assertThat(saved.path("ownerId").asString()).isEqualTo("9007199254741001");
            assertThat(saved.path("ownerName").asString()).isEqualTo("sn_hq");
            assertThat(saved.path("workerEnabled").asBoolean()).isFalse();
            assertThat(saveSchedule(b, true, 10).path("nextRunAt"))
                    .isEqualTo(saved.path("nextRunAt"));
            assertThat(saveSchedule(b, true, 20).path("nextRunAt"))
                    .isNotEqualTo(saved.path("nextRunAt"));
            for (int invalid : new int[] {0, 9, 10081})
                assertThat(
                                b.raw(
                                                "PUT",
                                                "/sn/orders/sync-schedule",
                                                Map.of("enabled", true, "intervalMinutes", invalid),
                                                b.token,
                                                b.csrf)
                                        .statusCode())
                        .isEqualTo(400);
            assertThat(saveSchedule(b, false, 20).path("nextRunAt").isNull()).isTrue();
            assertThat(schedules.runDue()).isFalse();
        }
        user(10, "schedule_bound", 5);
        try (var b = new Browser("schedule_bound")) {
            assertThat(b.get("/orders/sync-schedule").statusCode()).isEqualTo(200);
            assertThat(
                            b.raw(
                                            "PUT",
                                            "/sn/orders/sync-schedule",
                                            Map.of("enabled", true, "intervalMinutes", 10),
                                            b.token,
                                            b.csrf)
                                    .statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    void dueScheduleHasOneWinnerAndPublishesFullSnapshotAndLastResult() throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            saveSchedule(b, true, 10);
            assertThat(schedules.runDue()).isFalse();
            due();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var first =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return schedules.runDue();
                                });
                var second =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return schedules.runDue();
                                });
                start.countDown();
                assertThat(List.of(first.get(), second.get()))
                        .containsExactlyInAnyOrder(true, false);
            }
            var status = data(b.get("/orders/sync-schedule"));
            assertThat(status.path("lastState").asString()).isEqualTo("SUCCESS");
            assertThat(status.path("lastBills").asString()).isEqualTo("2");
            assertThat(status.path("lastRows").asString()).isEqualTo("4");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_order_sync WHERE mode='scheduled' AND state='SUCCESS'",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class))
                    .isEqualTo(1);
            assertThat(data(b.get("/orders")).path("total").asString()).isEqualTo("2");
            due();
            ERP.failure = "login";
            assertThat(schedules.runDue()).isTrue();
            var failed = data(b.get("/orders/sync-schedule"));
            assertThat(failed.path("lastState").asString()).isEqualTo("FAILED");
            assertThat(failed.path("lastErrorCode").asInt()).isEqualTo(1_005_000_002);
            assertThat(failed.path("lastBills").asString()).isEqualTo("2");
            assertThat(jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void schedulePreservesLastResultDuringFetchAndRecoversFromQueryFailure() throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            saveSchedule(b, true, 10);
            due();
            assertThat(schedules.runDue()).isTrue();
            assertThat(data(b.get("/orders/sync-schedule")).path("lastOk").asBoolean()).isTrue();
            due();
            var failed = scheduleControl.claim();
            var running = data(b.get("/orders/sync-schedule"));
            assertThat(running.path("lastState").asString()).isEqualTo("FETCHING");
            assertThat(running.path("lastOk").asBoolean()).isTrue();
            ERP.failure = "page";
            ERP.errorMessage = "比较查询失败";
            assertThatThrownBy(
                            () ->
                                    orderService.synchronizeScheduled(
                                            authorizations
                                                    .loadCurrent(failed.getActorId())
                                                    .orElseThrow(),
                                            failed.getId()))
                    .isInstanceOf(com.adtek.common.core.BusinessException.class);
            var result = data(b.get("/orders/sync-schedule"));
            assertThat(result.path("lastOk").asBoolean()).isFalse();
            assertThat(result.path("lastErrorMessage").asString()).isEqualTo("比较查询失败");
            assertThat(result.path("lastRows").asString()).isEqualTo("4");
            due();
            var recovery = scheduleControl.claim();
            var retry = data(b.get("/orders/sync-schedule"));
            assertThat(retry.path("lastState").asString()).isEqualTo("FETCHING");
            assertThat(retry.path("lastOk").asBoolean()).isFalse();
            assertThat(retry.path("lastErrorMessage").asString()).isEqualTo("比较查询失败");
            ERP.failure = "";
            orderService.synchronizeScheduled(
                    authorizations.loadCurrent(recovery.getActorId()).orElseThrow(),
                    recovery.getId());
            var success = data(b.get("/orders/sync-schedule"));
            assertThat(success.path("lastOk").asBoolean()).isTrue();
            assertThat(success.path("lastErrorMessage").asString()).isEmpty();
        }
    }

    @Test
    void scheduleRechecksDisabledForcedPasswordAndRevokedOwnersBeforeContactingErp()
            throws Exception {
        try (var b = new Browser("sn_hq")) {
            saveSchedule(b, true, 10);
        }
        for (String change :
                List.of(
                        "enabled=FALSE",
                        "enabled=TRUE,must_change_password=TRUE",
                        "must_change_password=FALSE")) {
            jdbc.update("UPDATE sys_user SET " + change + " WHERE username='sn_hq'");
            if (change.equals("must_change_password=FALSE"))
                jdbc.update("DELETE FROM sys_user_role");
            due();
            assertThat(schedules.runDue()).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_state FROM sn_order_sync_schedule", String.class))
                    .isEqualTo("FAILED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_error_code FROM sn_order_sync_schedule",
                                    Integer.class))
                    .isEqualTo(1_003_000_004);
            assertThat(ERP.envelopes).isEmpty();
        }
    }

    @Test
    void expiredRunIsRecoveredAndLateWorkerCannotPublishOrOverwriteReplacementResult()
            throws Exception {
        sample();
        try (var b = new Browser("sn_hq")) {
            saveSchedule(b, true, 10);
            due();
            var old = scheduleControl.claim();
            assertThat(old).isNotNull();
            assertThat(scheduleControl.claim()).isNull();
            jdbc.update(
                    "UPDATE sn_order_sync_schedule SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
            assertThat(schedules.runDue()).isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM sn_order_sync WHERE id=?",
                                    String.class,
                                    old.getId()))
                    .isEqualTo("FAILED");
            due();
            assertThat(schedules.runDue()).isTrue();
            var actor = authorizations.loadCurrent(old.getActorId()).orElseThrow();
            assertThatThrownBy(() -> orderService.synchronizeScheduled(actor, old.getId()))
                    .isInstanceOf(com.adtek.common.core.BusinessException.class);
            assertThat(data(b.get("/orders/sync-schedule")).path("lastState").asString())
                    .isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("SELECT revision FROM sn_snapshot_control", Long.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void settingsChangeAndAuthorizationRevocationDuringFetchFencePublication() throws Exception {
        sample();
        try (var b = new Browser("sn_hq");
                var pool = Executors.newSingleThreadExecutor()) {
            saveSchedule(b, true, 10);
            due();
            ERP.barrier = new CountDownLatch(2);
            var pending = pool.submit(() -> schedules.runDue());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ERP.barrier.getCount() == 2 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(ERP.barrier.getCount()).isEqualTo(1);
            saveSchedule(b, false, 10);
            ERP.barrier.countDown();
            assertThat(pending.get()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_order_bill", Integer.class))
                    .isZero();
            assertThat(data(b.get("/orders/sync-schedule")).path("lastErrorCode").asInt())
                    .isEqualTo(1_004_000_026);
            ERP.barrier = null;
            saveSchedule(b, true, 10);
            due();
            ERP.barrier = new CountDownLatch(2);
            var revoked = pool.submit(() -> schedules.runDue());
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ERP.barrier.getCount() == 2 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(ERP.barrier.getCount()).isEqualTo(1);
            jdbc.update(
                    "UPDATE sys_user SET enabled=FALSE,security_version=security_version+1 WHERE username='sn_hq'");
            ERP.barrier.countDown();
            assertThat(revoked.get()).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_state FROM sn_order_sync_schedule", String.class))
                    .isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_order_bill", Integer.class))
                    .isZero();
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
