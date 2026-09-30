package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.framework.security.RateLimitService;

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
import java.time.*;
import java.util.*;

/** 入口拒绝/当前范围/历史部门快照均通过真实HTTP与独立MySQL、Redis验证。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AuditIT {
    private static final String SECRET =
            HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
    private static final String PASSWORD = "Audit9!" + SECRET.substring(0, 24);

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_audit_it")
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
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired RateLimitService limits;
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
        jdbc.update("DELETE FROM sys_dept WHERE id>=2");
        jdbc.update(
                "INSERT INTO sys_dept(id,parent_id,dept_name) VALUES (2,1,'子部门'),(3,NULL,'外部门')");
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES"
                        + " (1000,'AUDITOR','审计员',3)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1000,150)");
        user(9007199254741001L, "audit_admin", 1, 1);
        user(10, "audit_reader", 1, 1000);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
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

    private void event(long id, Long actor, Long department, String action, String changes) {
        jdbc.update(
                "INSERT INTO"
                        + " sys_oper_log(id,user_id,actor_dept_id,module_name,action,success,changes,trace_id)"
                        + " VALUES (?,?,?,'测试审计',?,TRUE,?,?)",
                id,
                actor,
                department,
                action,
                changes,
                UUID.randomUUID().toString());
    }

    @Test
    void allFiveScopesApplyToPaginationDetailsAndMetadataWithoutLosingLargeIds() throws Exception {
        event(9007199254741101L, 10L, 1L, "fixture:scope", "{}");
        event(9007199254741102L, 11L, 1L, "fixture:scope", "{}");
        event(9007199254741103L, 12L, 2L, "fixture:scope", "{}");
        event(9007199254741104L, 13L, 3L, "fixture:scope", "{}");
        event(9007199254741105L, null, null, "fixture:scope", "{}");
        event(9007199254741106L, 10L, null, "fixture:scope", "{}");
        event(9007199254741107L, 13L, 3L, "fixture:private", "{}");
        jdbc.update("INSERT INTO sys_role_dept(role_id,dept_id) VALUES (1000,3)");
        try (var reader = new Browser("audit_reader")) {
            int[] expected = {6, 1, 2, 3, 2};
            for (int scope = 1; scope <= 5; scope++) {
                jdbc.update("UPDATE sys_role SET data_scope=? WHERE id=1000", scope);
                var response = reader.get("/audits?action=fixture:scope&size=2&page=1");
                var page = data(response);
                assertThat(page.path("total").asString())
                        .isEqualTo(Integer.toString(expected[scope - 1]));
                assertThat(page.path("items").size()).isEqualTo(Math.min(2, expected[scope - 1]));
                assertThat(page.path("items").get(0).path("id").isString()).isTrue();
                assertThat(page.path("items").get(0).has("changes")).isFalse();
            }
            jdbc.update("UPDATE sys_role SET data_scope=3 WHERE id=1000");
            assertThat(reader.get("/audits/9007199254741104").statusCode()).isEqualTo(404);
            assertThat(reader.get("/audits/9007199254741101").statusCode()).isEqualTo(200);
            assertThat(data(reader.get("/audits/options")).path("actions").toString())
                    .doesNotContain("fixture:private");
            jdbc.update("UPDATE sys_role SET data_scope=2 WHERE id=1000");
            jdbc.update("DELETE FROM sys_role_dept WHERE role_id=1000");
            assertThat(data(reader.get("/audits?action=fixture:scope")).path("total").asString())
                    .isEqualTo("0");
            assertThat(data(reader.get("/audits/options")).path("actions").size()).isZero();
        }
    }

    @Test
    void authenticationAndManagementSnapshotsRemainInOriginalDepartmentAfterMove()
            throws Exception {
        try (var reader = new Browser("audit_reader");
                var admin = new Browser("audit_admin")) {
            var login =
                    jdbc.queryForMap(
                            "SELECT id,actor_dept_id,actor_username FROM sys_oper_log WHERE"
                                    + " user_id=10 AND action='auth:LOGIN'");
            assertThat(((Number) login.get("actor_dept_id")).longValue()).isEqualTo(1L);
            assertThat(login.get("actor_username")).isEqualTo("audit_reader");
            var response =
                    admin.post(
                            "/users/10/update",
                            Map.of(
                                    "displayName",
                                    "调部门",
                                    "deptId",
                                    "3",
                                    "roleIds",
                                    List.of("1000"),
                                    "version",
                                    "1"),
                            true);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT actor_dept_id FROM sys_oper_log WHERE id=?",
                                    Long.class,
                                    login.get("id")))
                    .isEqualTo(1L);
            var managed =
                    jdbc.queryForMap(
                            "SELECT actor_dept_id,actor_username FROM sys_oper_log WHERE"
                                    + " action='system:user:update' AND success=TRUE");
            assertThat(((Number) managed.get("actor_dept_id")).longValue()).isEqualTo(1L);
            assertThat(managed.get("actor_username")).isEqualTo("audit_admin");
            try (var moved = new Browser("audit_reader")) {
                assertThat(moved.get("/audits/" + login.get("id")).statusCode()).isEqualTo(404);
            }
        }
    }

    @Test
    void entryAuthenticationCsrfAndMethodDenialsAreRecordedWithTrustedMetadataOnly()
            throws Exception {
        try (var anonymous = new Browser(null);
                var reader = new Browser("audit_reader")) {
            var missing = anonymous.get("/users?password=untrusted-query");
            assertThat(missing.statusCode()).isEqualTo(401);
            var missingLog = record(missing);
            assertThat(missingLog.get("user_id")).isNull();
            assertThat(missingLog.get("request_route")).isEqualTo("/api/v1/system/users");
            var csrf =
                    anonymous.post(
                            "/auth/login",
                            Map.of("username", "forged-user", "password", PASSWORD),
                            false);
            assertThat(csrf.statusCode()).isEqualTo(403);
            assertThat(((Number) record(csrf).get("error_code")).intValue()).isEqualTo(1003000004);
            anonymous.token = "invalid-token";
            var forged = anonymous.get("/users");
            assertThat(forged.statusCode()).isEqualTo(401);
            assertThat(record(forged).get("user_id")).isNull();
            var forbidden = reader.get("/users");
            assertThat(forbidden.statusCode()).isEqualTo(403);
            var trusted = record(forbidden);
            assertThat(((Number) trusted.get("user_id")).longValue()).isEqualTo(10L);
            assertThat(trusted.get("actor_username")).isEqualTo("audit_reader");
            String raw =
                    jdbc.queryForObject(
                            "SELECT CAST(changes AS CHAR) FROM sys_oper_log WHERE trace_id=?",
                            String.class,
                            trace(csrf));
            assertThat(raw).isEqualTo("{}");
            assertThat(json.writeValueAsString(trusted))
                    .doesNotContain(PASSWORD, "invalid-token", "forged-user", "untrusted-query");
        }
    }

    @Test
    void validationScopePreconditionsAndRateLimitsAlsoReachFailureAudit() throws Exception {
        try (var admin = new Browser("audit_admin");
                var anonymous = new Browser(null)) {
            var invalid = admin.postRaw("/users", "{broken-json", true);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(((Number) record(invalid).get("http_status")).intValue()).isEqualTo(400);
            jdbc.update(
                    "UPDATE sys_user SET must_change_password=TRUE WHERE username='audit_admin'");
            var blocked = admin.get("/users");
            assertThat(blocked.statusCode()).isEqualTo(403);
            assertThat(((Number) record(blocked).get("error_code")).intValue())
                    .isEqualTo(1003000005);
            for (int i = 0; i < 100; i++) {
                try {
                    limits.require("auth-login:ip", "127.0.0.1", 100, Duration.ofSeconds(60));
                } catch (com.adtek.common.core.BusinessException exhausted) {
                    assertThat(exhausted.getError().httpStatus()).isEqualTo(429);
                    break;
                }
            }
            var limited =
                    anonymous.post(
                            "/auth/login", Map.of("username", "x", "password", PASSWORD), false);
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();
            assertThat(((Number) record(limited).get("http_status")).intValue()).isEqualTo(429);
        }
    }

    @Test
    void existingBusinessFailuresAreNotDuplicatedAndAuditOutageCannotTurnDenialIntoSuccess()
            throws Exception {
        try (var admin = new Browser("audit_admin");
                var anonymous = new Browser(null)) {
            var rejected =
                    anonymous.post(
                            "/auth/login",
                            Map.of("username", "nonexistent", "password", PASSWORD),
                            true);
            assertThat(rejected.statusCode()).isEqualTo(401);
            assertThat(record(rejected).get("action")).isEqualTo("auth:LOGIN");
            var protectedWrite =
                    admin.post(
                            "/users/9007199254741001/update",
                            Map.of(
                                    "displayName",
                                    "保留管理员",
                                    "deptId",
                                    "1",
                                    "roleIds",
                                    List.of(),
                                    "version",
                                    "1"),
                            true);
            assertThat(protectedWrite.statusCode()).isEqualTo(409);
            assertThat(record(protectedWrite).get("action")).isEqualTo("system:user:update");
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_entry_audit_failure CHECK"
                            + " (action<>'http:REJECT')");
            try {
                var unavailable = anonymous.get("/users");
                assertThat(unavailable.statusCode()).isEqualTo(401);
                assertThat(json.readTree(unavailable.body()).path("code").asInt())
                        .isEqualTo(1003000001);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_entry_audit_failure");
            }
        }
    }

    @Test
    void detailsProjectOnlyKnownDiffFieldsAndFiltersRejectUnsafeOrUnboundedQueries()
            throws Exception {
        event(
                9007199254741111L,
                10L,
                1L,
                "fixture:details",
                """
                {"displayName":{"before":"旧名","after":"新名"},"password":{"before":"sensitive-value","after":"sensitive-value"},"roleIds":{"before":[],"after":["9007199254741001"]}}
                """);
        try (var admin = new Browser("audit_admin")) {
            var detail = data(admin.get("/audits/9007199254741111"));
            assertThat(detail.path("redacted").asBoolean()).isTrue();
            assertThat(detail.path("changes").size()).isEqualTo(2);
            assertThat(detail.toString()).doesNotContain("sensitive-value");
            assertThat(admin.get("/audits?size=101").statusCode()).isEqualTo(400);
            assertThat(
                            admin.get("/audits?from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z")
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(admin.get("/audits?traceId=not-a-uuid").statusCode()).isEqualTo(400);
            assertThat(admin.get("/audits?userId=9223372036854775808").statusCode()).isEqualTo(400);
            assertThat(
                            data(admin.get(
                                            "/audits?action="
                                                    + URLEncoder.encode(
                                                            "' OR 1=1 --",
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)))
                                    .path("total")
                                    .asString())
                    .isEqualTo("0");
            var trace =
                    jdbc.queryForObject(
                            "SELECT trace_id FROM sys_oper_log WHERE id=9007199254741111",
                            String.class);
            assertThat(data(admin.get("/audits?traceId=" + trace)).path("total").asString())
                    .isEqualTo("1");
        }
    }

    @Test
    void firewallRejectsAmbiguousPathWithoutStoringItsUntrustedSegments() throws Exception {
        try (var anonymous = new Browser(null)) {
            var response = anonymous.get("/users;untrusted-secret");
            assertThat(response.statusCode()).isEqualTo(403);
            var log = record(response);
            assertThat(log.get("request_route")).isEqualTo("/api/**");
            assertThat(log.get("user_id")).isNull();
            assertThat(json.writeValueAsString(log)).doesNotContain("untrusted-secret");
        }
    }

    private String trace(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body()).path("traceId").asString();
    }

    private Map<String, Object> record(HttpResponse<String> response) throws Exception {
        String trace = trace(response);
        for (int i = 0; i < 50; i++) {
            var rows = jdbc.queryForList("SELECT * FROM sys_oper_log WHERE trace_id=?", trace);
            if (!rows.isEmpty()) {
                assertThat(rows).hasSize(1);
                return rows.getFirst();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No audit record for response trace");
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
        String token;

        Browser(String username) throws Exception {
            if (username != null) {
                token =
                        data(post(
                                        "/auth/login",
                                        Map.of("username", username, "password", PASSWORD),
                                        true))
                                .path("accessToken")
                                .asString();
            }
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + "/api/v1/system" + path);
        }

        HttpResponse<String> get(String path) throws Exception {
            var request =
                    HttpRequest.newBuilder(uri(path)).header("X-Actor-Id", "9007199254741001");
            if (token != null && !path.equals("/auth/csrf")) {
                request.header("Authorization", "Bearer " + token);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body, boolean csrf) throws Exception {
            return postRaw(path, json.writeValueAsString(body), csrf);
        }

        HttpResponse<String> postRaw(String path, String body, boolean csrf) throws Exception {
            var request =
                    HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
            if (csrf) {
                var protection = data(get("/auth/csrf"));
                request.header(
                        protection.path("headerName").asString(),
                        protection.path("token").asString());
            }
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            return client.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
