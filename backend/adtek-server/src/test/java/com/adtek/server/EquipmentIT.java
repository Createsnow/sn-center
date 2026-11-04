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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.security.SecureRandom;
import java.util.*;

/** 设备台账用例只操作本类一次性 MySQL/Redis；不读取开发账号、密钥或持久卷。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class EquipmentIT {
    private static final String SECRET =
            HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
    private static final String PASSWORD = "Config9!" + SECRET.substring(0, 24);

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_equipment_it")
                    .withUsername("adtek_it")
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
    static void properties(DynamicPropertyRegistry p) {
        p.add("ADTEK_DB_URL", MYSQL::getJdbcUrl);
        p.add("ADTEK_DB_USER", MYSQL::getUsername);
        p.add("ADTEK_DB_PASSWORD", MYSQL::getPassword);
        p.add("ADTEK_MIGRATION_USER", MYSQL::getUsername);
        p.add("ADTEK_MIGRATION_PASSWORD", MYSQL::getPassword);
        p.add("ADTEK_REDIS_HOST", REDIS::getHost);
        p.add("ADTEK_REDIS_PORT", () -> REDIS.getMappedPort(6379));
        p.add("ADTEK_REDIS_PASSWORD", () -> SECRET);
        p.add("ADTEK_JWT_SECRET", () -> SECRET);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @Autowired com.adtek.framework.security.ResourceMembershipPort memberships;

    @BeforeEach
    void seed() {
        for (String table : List.of("equipment_asset", "sys_user_role", "sys_user", "sys_oper_log"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id>=1000");
        jdbc.update("DELETE FROM sys_role_dept WHERE role_id>=1000");
        jdbc.update("DELETE FROM sys_role WHERE id>=1000");
        jdbc.update("DELETE FROM sys_dept WHERE id>=1000");
        jdbc.execute("ALTER TABLE equipment_asset AUTO_INCREMENT=9007199254740993");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        user(11, "equipment_admin", 1L, 2);
        user(12, "equipment_one", 1L, 3);
        user(13, "equipment_two", 1L, 3);
    }

    private void user(long id, String name, Long department, long role) {
        jdbc.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name,dept_id,must_change_password) VALUES(?,?,?,?,?,FALSE)",
                id,
                name,
                passwords.encode(PASSWORD),
                name,
                department);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", id, role);
    }

    private Map<String, Object> create(String code) {
        return Map.of("code", code, "name", "测量设备", "enabled", true);
    }

    private Map<String, Object> update(String name, String version) {
        return Map.of("name", name, "version", version, "enabled", false);
    }

    private String create(Browser browser, String code) throws Exception {
        return data(browser.post("/equipment/assets", create(code))).path("id").asString();
    }

    @Test
    void createsReadsEditsAndAuditsExactIdentityWithTrustedOwnership() throws Exception {
        try (var browser = new Browser("equipment_one")) {
            var input = new HashMap<>(create("EQ-EXACT"));
            input.put("deptId", "999");
            input.put("createBy", "999");
            var created = data(browser.post("/equipment/assets", input));
            String id = created.path("id").asString();
            assertThat(id).isEqualTo("9007199254740993");
            assertThat(created.path("deptId").asString()).isEqualTo("1");
            assertThat(created.path("createBy").asString()).isEqualTo("12");
            assertThat(data(browser.get("/equipment/assets/" + id)).path("code").asString())
                    .isEqualTo("EQ-EXACT");
            var changed =
                    data(browser.post("/equipment/assets/" + id + "/update", update("校验设备", "1")));
            assertThat(changed.path("version").asString()).isEqualTo("2");
            assertThat(changed.path("enabled").asBoolean()).isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE action LIKE 'equipment:asset:%' AND success=TRUE",
                                    Integer.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT changes FROM sys_oper_log WHERE action='equipment:asset:update' AND success=TRUE",
                                    String.class))
                    .contains("测量设备", "校验设备")
                    .doesNotContain("password");
        }
    }

    @Test
    void paginatesAndFiltersWithinTheCurrentScope() throws Exception {
        try (var one = new Browser("equipment_one");
                var two = new Browser("equipment_two")) {
            String first = create(one, "PAGE-1");
            create(one, "PAGE-2");
            create(two, "PAGE-HIDDEN");
            data(one.post("/equipment/assets/" + first + "/update", update("停用", "1")));
            var page = data(one.get("/equipment/assets?keyword=PAGE&page=1&size=1"));
            assertThat(page.path("total").asString()).isEqualTo("2");
            assertThat(page.path("items").size()).isEqualTo(1);
            assertThat(data(one.get("/equipment/assets?enabled=false")).path("total").asString())
                    .isEqualTo("1");
            assertThat(data(one.get("/equipment/assets?keyword=HIDDEN")).path("total").asString())
                    .isEqualTo("0");
        }
    }

    @Test
    void preventsSelfScopeFromReadingOrEditingAnotherOwner() throws Exception {
        try (var one = new Browser("equipment_one");
                var two = new Browser("equipment_two")) {
            String id = create(one, "PRIVATE-1");
            assertThat(two.get("/equipment/assets/" + id).statusCode()).isEqualTo(404);
            assertThat(
                            two.post("/equipment/assets/" + id + "/update", update("越界", "1"))
                                    .statusCode())
                    .isEqualTo(404);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT version FROM equipment_asset WHERE id=?",
                                    Long.class,
                                    id))
                    .isEqualTo(1);
        }
    }

    @Test
    void listPermissionDoesNotGrantDetailOrMutation() throws Exception {
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES(1000,'ONLY_LIST','仅读列表',1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1000,210)");
        user(14, "equipment_reader", 1L, 1000);
        try (var admin = new Browser("equipment_admin");
                var reader = new Browser("equipment_reader")) {
            String id = create(admin, "LIST-ONLY");
            assertThat(data(reader.get("/equipment/assets")).path("total").asString())
                    .isEqualTo("1");
            assertThat(reader.get("/equipment/assets/" + id).statusCode()).isEqualTo(403);
            assertThat(reader.post("/equipment/assets", create("DENIED")).statusCode())
                    .isEqualTo(403);
            assertThat(
                            reader.post(
                                            "/equipment/assets/" + id + "/update",
                                            update("DENIED", "1"))
                                    .statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    void globalReadRoleDoesNotBroadenSelfWrite() throws Exception {
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES(1000,'GLOBAL_READ','全局读',1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1000,210),(1000,211)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(12,1000)");
        try (var one = new Browser("equipment_one");
                var two = new Browser("equipment_two")) {
            String id = create(two, "GLOBAL-READ");
            assertThat(data(one.get("/equipment/assets/" + id)).path("id").asString())
                    .isEqualTo(id);
            assertThat(
                            one.post(
                                            "/equipment/assets/" + id + "/update",
                                            update("UNAUTHORIZED", "1"))
                                    .statusCode())
                    .isEqualTo(404);
        }
    }

    @Test
    void departmentScopeLimitsAssetRowsAndCounts() throws Exception {
        jdbc.update("INSERT INTO sys_dept(id,parent_id,dept_name) VALUES(1000,1,'其他部门')");
        user(14, "equipment_otherdept", 1000L, 3);
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES(1000,'DEPT_READ','部门读',3)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1000,210),(1000,211)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(12,1000)");
        try (var one = new Browser("equipment_one");
                var two = new Browser("equipment_two");
                var other = new Browser("equipment_otherdept")) {
            String visible = create(two, "SAME-DEPT"), hidden = create(other, "OTHER-DEPT");
            assertThat(data(one.get("/equipment/assets")).path("total").asString()).isEqualTo("1");
            assertThat(one.get("/equipment/assets/" + visible).statusCode()).isEqualTo(200);
            assertThat(one.get("/equipment/assets/" + hidden).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void rejectsDuplicateCodeAndInvalidInputsWithoutSuccessfulAudit() throws Exception {
        try (var one = new Browser("equipment_one")) {
            String id = create(one, "UNIQUE");
            var duplicate = one.post("/equipment/assets", create("UNIQUE"));
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(json.readTree(duplicate.body()).path("code").asInt()).isEqualTo(1004000001);
            for (Object input :
                    List.of(
                            create("lowercase"),
                            create("X".repeat(65)),
                            Map.of("code", "VALID", "name", " ", "enabled", true),
                            Map.of("code", "VALID", "name", "字".repeat(129), "enabled", true)))
                assertThat(one.post("/equipment/assets", input).statusCode()).isEqualTo(400);
            assertThat(
                            one.post(
                                            "/equipment/assets/" + id + "/update",
                                            update("BAD", "9999999999999999999"))
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(one.get("/equipment/assets?size=101").statusCode()).isEqualTo(400);
            assertThat(one.get("/equipment/assets/9999999999999999999").statusCode())
                    .isEqualTo(400);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_asset", Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE action LIKE 'equipment:asset:%' AND success=TRUE",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void missingDepartmentAndUnimplementedResourceMembershipFailClosed() throws Exception {
        jdbc.update("UPDATE sys_user SET dept_id=NULL WHERE id=12");
        try (var one = new Browser("equipment_one")) {
            var response = one.post("/equipment/assets", create("NO-DEPT"));
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(1004000003);
        }
        assertThat(memberships.resourceIds(12)).isEmpty();
        assertThatThrownBy(() -> memberships.replace(null, 12, Set.of(99L)))
                .isInstanceOf(com.adtek.common.core.BusinessException.class);
    }

    @Test
    void staleVersionPreservesCurrentRecord() throws Exception {
        try (var one = new Browser("equipment_one")) {
            String id = create(one, "VERSION");
            data(one.post("/equipment/assets/" + id + "/update", update("新值", "1")));
            var stale = one.post("/equipment/assets/" + id + "/update", update("旧表单", "1"));
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(json.readTree(stale.body()).path("code").asInt()).isEqualTo(1004000002);
            assertThat(data(one.get("/equipment/assets/" + id)).path("name").asString())
                    .isEqualTo("新值");
        }
    }

    @Test
    void concurrentUpdatesHaveOneWinner() throws Exception {
        try (var first = new Browser("equipment_one");
                var second = new Browser("equipment_one");
                var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            String id = create(first, "CONCURRENT");
            var gate = new java.util.concurrent.CountDownLatch(1);
            var a =
                    executor.submit(
                            () -> {
                                gate.await();
                                return first.post(
                                                "/equipment/assets/" + id + "/update",
                                                update("ONE", "1"))
                                        .statusCode();
                            });
            var b =
                    executor.submit(
                            () -> {
                                gate.await();
                                return second.post(
                                                "/equipment/assets/" + id + "/update",
                                                update("TWO", "1"))
                                        .statusCode();
                            });
            gate.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT version FROM equipment_asset WHERE id=?",
                                    Long.class,
                                    id))
                    .isEqualTo(2);
        }
    }

    @Test
    void concurrentCreatesRelyOnDatabaseUniqueness() throws Exception {
        try (var first = new Browser("equipment_one");
                var second = new Browser("equipment_two");
                var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var a =
                    executor.submit(
                            () -> {
                                gate.await();
                                return first.post("/equipment/assets", create("RACE")).statusCode();
                            });
            var b =
                    executor.submit(
                            () -> {
                                gate.await();
                                return second.post("/equipment/assets", create("RACE"))
                                        .statusCode();
                            });
            gate.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_asset", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void auditFailureRollsBackBusinessCreationAndUpdate() throws Exception {
        try (var one = new Browser("equipment_one")) {
            String id = create(one, "AUDIT-EXISTING");
            jdbc.update("DELETE FROM sys_oper_log");
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_equipment_audit CHECK(NOT(action LIKE 'equipment:asset:%' AND success=TRUE))");
            try {
                assertThat(one.post("/equipment/assets", create("AUDIT-ROLLBACK")).statusCode())
                        .isEqualTo(500);
                assertThat(
                                one.post(
                                                "/equipment/assets/" + id + "/update",
                                                update("ROLLBACK", "1"))
                                        .statusCode())
                        .isEqualTo(500);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM equipment_asset", Integer.class))
                        .isEqualTo(1);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT version FROM equipment_asset WHERE id=?",
                                        Long.class,
                                        id))
                        .isEqualTo(1);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_equipment_audit");
            }
        }
    }

    @Test
    void permissionRevocationRejectsAnExistingSession() throws Exception {
        try (var one = new Browser("equipment_one")) {
            jdbc.update("DELETE FROM sys_user_role WHERE user_id=12");
            assertThat(one.post("/equipment/assets", create("REVOKED")).statusCode())
                    .isEqualTo(403);
            jdbc.update(
                    "UPDATE sys_user SET enabled=FALSE,security_version=security_version+1 WHERE id=12");
            assertThat(one.get("/equipment/assets").statusCode()).isEqualTo(401);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM equipment_asset", Integer.class))
                    .isZero();
        }
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    private final class Browser implements AutoCloseable {
        private final HttpClient client =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        private String token;

        Browser(String username) throws Exception {
            var response =
                    post("/system/auth/login", Map.of("username", username, "password", PASSWORD));
            token = data(response).path("accessToken").asString();
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + "/api/v1" + path);
        }

        HttpResponse<String> get(String path) throws Exception {
            var request = HttpRequest.newBuilder(uri(path));
            if (token != null && !path.equals("/system/auth/csrf")) {
                request.header("Authorization", "Bearer " + token);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body) throws Exception {
            var csrf = data(get("/system/auth/csrf"));
            var request =
                    HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header(
                                    csrf.path("headerName").asString(),
                                    csrf.path("token").asString());
            if (token != null && !path.equals("/system/auth/refresh")) {
                request.header("Authorization", "Bearer " + token);
            }
            return client.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
