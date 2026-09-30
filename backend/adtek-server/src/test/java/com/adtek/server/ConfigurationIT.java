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

/** 配置安全用例只操作本类一次性 MySQL/Redis；不读取开发账号、密钥或持久卷。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ConfigurationIT {
    private static final String SECRET =
            HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
    private static final String PASSWORD = "Config9!" + SECRET.substring(0, 24);

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_configuration_it")
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

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM sys_user_role");
        jdbc.update("DELETE FROM sn_user_factory");
        jdbc.update("DELETE FROM sys_user");
        jdbc.update("DELETE FROM sys_oper_log");
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id>=1000");
        jdbc.update("DELETE FROM sys_role_dept WHERE role_id>=1000");
        jdbc.update("DELETE FROM sys_role WHERE id>=1000");
        jdbc.update("DELETE FROM sys_dept WHERE id>=1000");
        jdbc.update("DELETE FROM sys_menu WHERE id>=200");
        jdbc.update("UPDATE sys_menu SET enabled=TRUE,version=1 WHERE id<200");
        jdbc.update("DELETE FROM sys_dict_item");
        jdbc.update("DELETE FROM sys_dict_type");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        user(9007199254741001L, "config_admin", 1, 1);
    }

    private void user(long id, String name, long department, long role) {
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (?,?,?,?,?,FALSE)",
                id,
                name,
                passwords.encode(PASSWORD),
                name,
                department);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)", id, role);
    }

    private void role(long id, String code, int scope, long... menus) {
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES (?,?,?,?)",
                id,
                code,
                code,
                scope);
        for (long menu : menus) {
            jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (?,?)", id, menu);
        }
    }

    private Map<String, Object> roleCommand(
            String code, List<String> menus, List<String> departments, int scope, String version) {
        var result = new HashMap<String, Object>();
        result.put("code", code);
        result.put("name", "配置角色");
        result.put("enabled", true);
        result.put("dataScope", scope);
        result.put("factoryScope", 1);
        result.put("menuIds", menus);
        result.put("deptIds", departments);
        if (version != null) {
            result.put("version", version);
        }
        return result;
    }

    @Test
    void roleScopeUpdateRevokesAllMembersAndRejectsStaleVersion() throws Exception {
        role(1000, "READERS", 1, 110);
        user(10, "reader_one", 1, 1000);
        user(11, "reader_two", 1, 1000);
        try (var admin = new Browser("config_admin");
                var one = new Browser("reader_one");
                var two = new Browser("reader_two")) {
            var response =
                    admin.post(
                            "/roles/1000/update",
                            roleCommand("READERS", List.of("110"), List.of(), 2, "1"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(data(response).path("reauthenticate").asBoolean()).isFalse();
            assertThat(one.get("/auth/me").statusCode()).isEqualTo(401);
            assertThat(two.post("/auth/refresh", Map.of()).statusCode()).isEqualTo(401);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT security_version FROM sys_user WHERE id=10",
                                    Long.class))
                    .isEqualTo(2L);
            assertThat(
                            admin.post(
                                            "/roles/1000/update",
                                            roleCommand(
                                                    "READERS", List.of("110"), List.of(), 1, "1"))
                                    .statusCode())
                    .isEqualTo(409);
            var log =
                    jdbc.queryForObject(
                            "SELECT CAST(changes AS CHAR) FROM sys_oper_log WHERE"
                                    + " action='system:role:update' AND success=TRUE",
                            String.class);
            assertThat(log).contains("dataScope").doesNotContain("password", "menuIds");
            try (var reader = new Browser("reader_one")) {
                assertThat(data(reader.get("/users")).path("total").asString()).isEqualTo("0");
            }
        }
    }

    @Test
    void
            globalConfigurationRequiresExplicitAuthorityAndCannotDelegateUnknownPermissionOrFactoryScope()
                    throws Exception {
        role(1000, "LIMITED", 1, 120, 121, 199);
        user(10, "limited", 1, 1000);
        role(1001, "LOCAL_ADMIN", 5, 120, 121, 199);
        user(11, "local", 1, 1001);
        try (var limited = new Browser("limited");
                var local = new Browser("local");
                var admin = new Browser("config_admin")) {
            assertThat(local.get("/roles").statusCode()).isEqualTo(403);
            assertThat(
                            limited.post(
                                            "/roles",
                                            roleCommand(
                                                    "ESCALATION",
                                                    List.of("110"),
                                                    List.of(),
                                                    1,
                                                    null))
                                    .statusCode())
                    .isEqualTo(403);
            var factory = roleCommand("FACTORIES", List.of("110"), List.of(), 1, null);
            factory.put("factoryScope", 2);
            assertThat(admin.post("/roles", factory).statusCode()).isEqualTo(403);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_role WHERE role_code IN"
                                            + " ('ESCALATION','FACTORIES')",
                                    Integer.class))
                    .isZero();
        }
    }

    @Test
    void lastAdministratorAndSelfLockoutAreRolledBack() throws Exception {
        try (var admin = new Browser("config_admin")) {
            var command = roleCommand("PLATFORM_ADMIN", List.of("120", "121"), List.of(), 1, "1");
            var response = admin.post("/roles/1/update", command);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(1002000010);
            assertThat(jdbc.queryForObject("SELECT version FROM sys_role WHERE id=1", Long.class))
                    .isEqualTo(1L);
            assertThat(admin.get("/auth/me").statusCode()).isEqualTo(200);
            role(1000, "BACKUP", 1, 199);
            user(10, "backup", 1, 1000);
            response = admin.post("/roles/1/update", command);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(1002000009);
        }
    }

    @Test
    void departmentMoveRecomputesDescendantScopeAndRejectsCycleOrActiveReferences()
            throws Exception {
        jdbc.update(
                "INSERT INTO sys_dept(id,parent_id,dept_name) VALUES"
                        + " (1000,NULL,'A'),(1001,1000,'B'),(1002,NULL,'C')");
        role(1000, "TREE", 4, 110);
        user(10, "tree_reader", 1000, 1000);
        try (var admin = new Browser("config_admin");
                var reader = new Browser("tree_reader")) {
            assertThat(
                            admin.post(
                                            "/departments/1000/update",
                                            Map.of(
                                                    "name",
                                                    "A",
                                                    "parentId",
                                                    "1001",
                                                    "enabled",
                                                    true,
                                                    "version",
                                                    "1"))
                                    .statusCode())
                    .isEqualTo(409);
            var response =
                    admin.post(
                            "/departments/1001/update",
                            Map.of(
                                    "name",
                                    "B",
                                    "parentId",
                                    "1002",
                                    "enabled",
                                    true,
                                    "version",
                                    "1"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(reader.get("/auth/me").statusCode()).isEqualTo(401);
            assertThat(admin.get("/auth/me").statusCode()).isEqualTo(200);
            assertThat(
                            admin.post(
                                            "/departments/1000/update",
                                            Map.of("name", "A", "enabled", false, "version", "1"))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            admin.post(
                                            "/departments/1002/update",
                                            Map.of("name", "C", "enabled", false, "version", "1"))
                                    .statusCode())
                    .isEqualTo(409);
        }
    }

    @Test
    void menuHierarchyWhitelistAndPermissionImmutabilityAreEnforced() throws Exception {
        try (var admin = new Browser("config_admin")) {
            String root =
                    data(admin.post(
                                    "/menus",
                                    Map.of(
                                            "name",
                                            "配置目录",
                                            "menuType",
                                            "M",
                                            "enabled",
                                            true,
                                            "sortOrder",
                                            1)))
                            .path("id")
                            .asString();
            String child =
                    data(admin.post(
                                    "/menus",
                                    Map.of(
                                            "name",
                                            "子目录",
                                            "menuType",
                                            "M",
                                            "parentId",
                                            root,
                                            "enabled",
                                            true,
                                            "sortOrder",
                                            2)))
                            .path("id")
                            .asString();
            assertThat(
                            admin.post(
                                            "/menus/" + root + "/update",
                                            Map.of(
                                                    "name",
                                                    "配置目录",
                                                    "menuType",
                                                    "M",
                                                    "parentId",
                                                    child,
                                                    "enabled",
                                                    true,
                                                    "sortOrder",
                                                    1,
                                                    "version",
                                                    "1"))
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            admin.post(
                                            "/menus/" + root + "/update",
                                            Map.of(
                                                    "name",
                                                    "配置目录",
                                                    "menuType",
                                                    "M",
                                                    "enabled",
                                                    false,
                                                    "sortOrder",
                                                    1,
                                                    "version",
                                                    "1"))
                                    .statusCode())
                    .isEqualTo(409);
            var malicious =
                    new HashMap<String, Object>(
                            Map.of(
                                    "name",
                                    "恶意组件",
                                    "menuType",
                                    "C",
                                    "parentId",
                                    "100",
                                    "enabled",
                                    true,
                                    "sortOrder",
                                    1,
                                    "permission",
                                    "system:user:list",
                                    "component",
                                    "https://evil.test/app",
                                    "path",
                                    "/system/users",
                                    "version",
                                    "1"));
            assertThat(admin.post("/menus/110/update", malicious).statusCode()).isEqualTo(400);
            malicious.put("component", "system.users");
            malicious.put("permission", "system:user:update");
            assertThat(admin.post("/menus/110/update", malicious).statusCode()).isEqualTo(400);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT permission_code FROM sys_menu WHERE id=110",
                                    String.class))
                    .isEqualTo("system:user:list");
        }
    }

    @Test
    void disablingButtonRevokesAffectedSessionsAndLeavesUnrelatedAccountVersionUntouched()
            throws Exception {
        user(10, "unrelated", 1, 3);
        try (var admin = new Browser("config_admin")) {
            var response =
                    admin.post(
                            "/menus/111/update",
                            Map.of(
                                    "name",
                                    "menu.userAdd",
                                    "menuType",
                                    "F",
                                    "parentId",
                                    "110",
                                    "permission",
                                    "system:user:add",
                                    "enabled",
                                    false,
                                    "sortOrder",
                                    1,
                                    "version",
                                    "1"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(data(response).path("reauthenticate").asBoolean()).isTrue();
            assertThat(admin.get("/auth/me").statusCode()).isEqualTo(401);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT security_version FROM sys_user WHERE id=10",
                                    Long.class))
                    .isEqualTo(1L);
        }
    }

    @Test
    void dictionaryUniquenessThreeLanguagesImmutableValuesAndDisabledParentArePreserved()
            throws Exception {
        try (var admin = new Browser("config_admin")) {
            var type = Map.of("code", "PRINT_STATE", "name", "打印状态", "enabled", true);
            var added = admin.post("/dictionary-types", type);
            assertThat(added.statusCode()).isEqualTo(200);
            String typeId = data(added).path("id").asString();
            assertThat(admin.post("/dictionary-types", type).statusCode()).isEqualTo(409);
            var item =
                    new HashMap<String, Object>(
                            Map.of(
                                    "typeId",
                                    typeId,
                                    "value",
                                    "PRINTED",
                                    "labelZh",
                                    "已打印",
                                    "labelEn",
                                    "Printed",
                                    "labelVi",
                                    "Đã in",
                                    "sortOrder",
                                    1,
                                    "enabled",
                                    true));
            var created = admin.post("/dictionary-items", item);
            assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
            String itemId = data(created).path("id").asString();
            assertThat(admin.post("/dictionary-items", item).statusCode()).isEqualTo(409);
            assertThat(
                            data(admin.get("/dictionary-items?typeId=" + typeId))
                                    .get(0)
                                    .path("labelVi")
                                    .asString())
                    .isEqualTo("Đã in");
            item.put("version", "1");
            item.put("value", "REWRITTEN");
            assertThat(admin.post("/dictionary-items/" + itemId + "/update", item).statusCode())
                    .isEqualTo(400);
            assertThat(
                            admin.post(
                                            "/dictionary-types/" + typeId + "/update",
                                            Map.of(
                                                    "code",
                                                    "PRINT_STATE",
                                                    "name",
                                                    "打印状态",
                                                    "enabled",
                                                    false,
                                                    "version",
                                                    "1"))
                                    .statusCode())
                    .isEqualTo(200);
            item.put("value", "PRINTED");
            assertThat(admin.post("/dictionary-items/" + itemId + "/update", item).statusCode())
                    .isEqualTo(409);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE business_type='INSERT'"
                                            + " AND success=TRUE AND action='system:dict:update'",
                                    Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void auditFailureRollsBackRoleRelationsAndVersionInvalidation() throws Exception {
        role(1000, "ROLLBACK", 1, 110);
        user(10, "rollback_reader", 1, 1000);
        try (var admin = new Browser("config_admin");
                var reader = new Browser("rollback_reader")) {
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_config_audit CHECK"
                            + " (NOT(action='system:role:update' AND success=TRUE))");
            try {
                var response =
                        admin.post(
                                "/roles/1000/update",
                                roleCommand("ROLLBACK", List.of(), List.of(), 5, "1"));
                assertThat(response.statusCode()).isEqualTo(500);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT menu_id FROM sys_role_menu WHERE role_id=1000",
                                        Long.class))
                        .isEqualTo(110L);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT version FROM sys_role WHERE id=1000", Long.class))
                        .isEqualTo(1L);
                assertThat(reader.get("/auth/me").statusCode()).isEqualTo(200);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_config_audit");
            }
        }
    }

    @Test
    void concurrentRoleWritesHaveOneWinnerAndOneVersionConflict() throws Exception {
        role(1000, "CONCURRENT", 1, 110);
        try (var first = new Browser("config_admin");
                var second = new Browser("config_admin");
                var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var command = roleCommand("CONCURRENT", List.of("110"), List.of(), 5, "1");
            var one =
                    executor.submit(
                            () -> {
                                gate.await();
                                return first.post("/roles/1000/update", command).statusCode();
                            });
            var two =
                    executor.submit(
                            () -> {
                                gate.await();
                                return second.post("/roles/1000/update", command).statusCode();
                            });
            gate.countDown();
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT version FROM sys_role WHERE id=1000", Long.class))
                    .isEqualTo(2L);
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
            var response = post("/auth/login", Map.of("username", username, "password", PASSWORD));
            token = data(response).path("accessToken").asString();
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + "/api/v1/system" + path);
        }

        HttpResponse<String> get(String path) throws Exception {
            var request = HttpRequest.newBuilder(uri(path));
            if (token != null && !path.equals("/auth/csrf")) {
                request.header("Authorization", "Bearer " + token);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body) throws Exception {
            var csrf = data(get("/auth/csrf"));
            var request =
                    HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header(
                                    csrf.path("headerName").asString(),
                                    csrf.path("token").asString());
            if (token != null && !path.equals("/auth/refresh")) {
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
