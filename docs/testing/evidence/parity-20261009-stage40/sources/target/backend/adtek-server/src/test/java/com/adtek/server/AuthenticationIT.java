package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.AuthorizationPort;
import com.adtek.system.dto.BootstrapAdminDTO;
import com.adtek.system.service.BootstrapService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/** HTTP 认证闭环使用独立一次性真实设施；不加载 Compose 密钥，不操作开发卷。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AuthenticationIT {
    private static final String SECRET =
            HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
    private static final String INITIAL_PASSWORD = "It9!" + SECRET.substring(0, 20);
    private static final String CHANGED_PASSWORD = "New8!" + SECRET.substring(20, 40);
    private static final long USER_ID = 9_007_199_254_741_001L;

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_auth_it")
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
    static void properties(DynamicPropertyRegistry properties) {
        // 与运行环境一致固定 JDBC 会话时区；DATETIME 不携带时区，不能依赖测试宿主机默认值。
        properties.add(
                "ADTEK_DB_URL",
                () ->
                        MYSQL.getJdbcUrl()
                                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        properties.add("ADTEK_DB_USER", MYSQL::getUsername);
        properties.add("ADTEK_DB_PASSWORD", MYSQL::getPassword);
        properties.add("ADTEK_MIGRATION_USER", MYSQL::getUsername);
        properties.add("ADTEK_MIGRATION_PASSWORD", MYSQL::getPassword);
        properties.add("ADTEK_REDIS_HOST", REDIS::getHost);
        properties.add("ADTEK_REDIS_PORT", () -> REDIS.getMappedPort(6379));
        properties.add("ADTEK_REDIS_PASSWORD", () -> SECRET);
        properties.add("ADTEK_JWT_SECRET", () -> SECRET);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired BootstrapService bootstrap;
    @Autowired AuthorizationPort authorizations;
    @Autowired ObjectMapper json;
    @Autowired com.adtek.system.service.support.LoginFailureGuard loginGuard;
    @LocalServerPort int port;

    @BeforeEach
    void resetOnlyDisposableTestData() {
        jdbc.update("DELETE FROM sn_audit_identity");
        jdbc.update("DELETE FROM sn_audit");
        jdbc.update("DELETE FROM sys_user_role");
        jdbc.update("DELETE FROM sn_user_factory");
        jdbc.update("DELETE FROM sys_user");
        jdbc.update("DELETE FROM sys_oper_log");
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id >= 1000");
        jdbc.update("DELETE FROM sys_role_dept WHERE role_id >= 1000");
        jdbc.update("DELETE FROM sys_role WHERE id >= 1000");
        jdbc.update("DELETE FROM sys_dept WHERE id >= 1000");
        jdbc.update("UPDATE sys_security_control SET initialized=FALSE WHERE id=1");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void redisOutageReturns503ForAccessAndLoginWithoutAcceptingAnOldAuthorizationSnapshot()
            throws Exception {
        seedUser(false);
        try (var browser = new Browser()) {
            var login = browser.login(INITIAL_PASSWORD);
            assertThat(login.statusCode()).isEqualTo(200);
            String access = json.readTree(login.body()).path("data").path("accessToken").asString();
            var docker = REDIS.getDockerClient();
            docker.pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                var response = browser.get("me", access);
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(json.readTree(response.body()).path("code").asInt())
                        .isEqualTo(1_003_000_002);
                assertThat(browser.login(INITIAL_PASSWORD).statusCode()).isEqualTo(503);
            } finally {
                docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
            }
            assertThat(browser.get("me", access).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void snAuthenticationFactsKeepIdentityFailuresAndPasswordChangesWithoutCredentials()
            throws Exception {
        seedUser(false);
        try (var browser = new Browser()) {
            assertThat(
                            browser.post(
                                            "login",
                                            Map.of(
                                                    "username",
                                                    "missing_auth_it",
                                                    "password",
                                                    INITIAL_PASSWORD),
                                            null,
                                            true)
                                    .statusCode())
                    .isEqualTo(401);
            var anonymous =
                    jdbc.queryForMap("SELECT * FROM sn_audit WHERE operator='missing_auth_it'");
            assertThat(anonymous.get("actor_id")).isNull();
            assertThat(anonymous.get("operator_name")).isEqualTo("");
            assertThat(anonymous.get("roles")).isEqualTo("[]");
            assertThat(anonymous.get("quantity")).isNull();
            assertThat(anonymous.get("error_message")).isEqualTo("工号或密码错误");
            assertThat(browser.login(CHANGED_PASSWORD).statusCode()).isEqualTo(401);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT actor_id FROM sn_audit WHERE operator='auth_it'",
                                    Long.class))
                    .isEqualTo(USER_ID);
            jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=?", USER_ID);
            var disabled = browser.login(INITIAL_PASSWORD);
            assertThat(json.readTree(disabled.body()).path("code").asInt())
                    .isEqualTo(1_002_000_018);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_audit WHERE error_message='账户已停用'",
                                    Integer.class))
                    .isEqualTo(1);
            jdbc.update("UPDATE sys_user SET enabled=TRUE WHERE id=?", USER_ID);
            var login = browser.login(INITIAL_PASSWORD);
            String token = json.readTree(login.body()).path("data").path("accessToken").asString();
            assertThat(
                            browser.post(
                                            "password",
                                            Map.of(
                                                    "oldPassword",
                                                    CHANGED_PASSWORD,
                                                    "newPassword",
                                                    INITIAL_PASSWORD),
                                            token,
                                            true)
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            browser.post(
                                            "password",
                                            Map.of(
                                                    "oldPassword",
                                                    INITIAL_PASSWORD,
                                                    "newPassword",
                                                    INITIAL_PASSWORD),
                                            token,
                                            true)
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            browser.post(
                                            "password",
                                            Map.of(
                                                    "oldPassword",
                                                    INITIAL_PASSWORD,
                                                    "newPassword",
                                                    "weak"),
                                            token,
                                            true)
                                    .statusCode())
                    .isEqualTo(400);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT error_message FROM sn_audit WHERE action='auth:PASSWORD_CHANGE'",
                                    String.class))
                    .containsExactlyInAnyOrder("原密码不正确", "新密码不能与原密码相同", "密码须为 8–64 位，且同时包含字母和数字");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_audit WHERE action='auth:PASSWORD_CHANGE' AND detail_text='POST /api/auth/password' AND factory_id IS NULL AND quantity IS NULL",
                                    Integer.class))
                    .isEqualTo(3);
        }
        for (var row : jdbc.queryForList("SELECT * FROM sn_audit")) {
            assertThat(row.toString())
                    .doesNotContain(INITIAL_PASSWORD, CHANGED_PASSWORD, "198.51.100.");
            assertThat(row.get("trace_id")).isNotNull();
        }
    }

    @Test
    void authenticationProjectionFailureRollsBackSuccessAndCannotReplacePasswordRejection()
            throws Exception {
        seedUser(false);
        String originalHash =
                jdbc.queryForObject(
                        "SELECT password_hash FROM sys_user WHERE id=?", String.class, USER_ID);
        try (var browser = new Browser()) {
            var login = browser.login(INITIAL_PASSWORD);
            String access = json.readTree(login.body()).path("data").path("accessToken").asString();
            jdbc.execute(
                    "ALTER TABLE sn_audit ADD CONSTRAINT ck_auth_projection CHECK(action<>'auth:PASSWORD_CHANGE')");
            try {
                assertThat(
                                browser.post(
                                                "password",
                                                Map.of(
                                                        "oldPassword",
                                                        INITIAL_PASSWORD,
                                                        "newPassword",
                                                        CHANGED_PASSWORD),
                                                access,
                                                true)
                                        .statusCode())
                        .isEqualTo(500);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT password_hash FROM sys_user WHERE id=?",
                                        String.class,
                                        USER_ID))
                        .isEqualTo(originalHash);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT security_version FROM sys_user WHERE id=?",
                                        Long.class,
                                        USER_ID))
                        .isEqualTo(1);
                assertThat(
                                browser.post(
                                                "password",
                                                Map.of(
                                                        "oldPassword",
                                                        CHANGED_PASSWORD,
                                                        "newPassword",
                                                        INITIAL_PASSWORD),
                                                access,
                                                true)
                                        .statusCode())
                        .isEqualTo(400);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_oper_log WHERE action='auth:PASSWORD_CHANGE'",
                                        Integer.class))
                        .isZero();
            } finally {
                jdbc.execute("ALTER TABLE sn_audit DROP CHECK ck_auth_projection");
            }
        }
    }

    @Test
    void taggedLegacyPasswordLogsInAndUpgradesWithoutClearingRequiredChange() throws Exception {
        seedUser(true);
        var old =
                new org.springframework.security.crypto.password.Pbkdf2PasswordEncoder(
                        "",
                        16,
                        120000,
                        org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
                                .SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        String legacy = "{pbkdf2-sha256-120k}" + old.encode(INITIAL_PASSWORD);
        jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?", legacy, USER_ID);
        try (var browser = new Browser()) {
            assertThat(browser.login(CHANGED_PASSWORD).statusCode()).isEqualTo(401);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT password_hash FROM sys_user WHERE id=?",
                                    String.class,
                                    USER_ID))
                    .isEqualTo(legacy);
            var response = browser.login(INITIAL_PASSWORD);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(
                            json.readTree(response.body())
                                    .path("data")
                                    .path("profile")
                                    .path("mustChangePassword")
                                    .asBoolean())
                    .isTrue();
        }
        var upgraded =
                jdbc.queryForObject(
                        "SELECT password_hash FROM sys_user WHERE id=?", String.class, USER_ID);
        assertThat(upgraded).startsWith("{pbkdf2-sha256-600k}");
        assertThat(passwords.matches(INITIAL_PASSWORD, upgraded)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT security_version FROM sys_user WHERE id=?",
                                Long.class,
                                USER_ID))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT must_change_password FROM sys_user WHERE id=?",
                                Boolean.class,
                                USER_ID))
                .isTrue();
    }

    @Test
    void disabledLegacyAccountAndUntaggedHashAreNotAccepted() throws Exception {
        seedUser(false);
        var old =
                new org.springframework.security.crypto.password.Pbkdf2PasswordEncoder(
                        "",
                        16,
                        120000,
                        org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
                                .SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        String legacy = old.encode(INITIAL_PASSWORD);
        try (var browser = new Browser()) {
            jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?", legacy, USER_ID);
            assertThat(browser.login(INITIAL_PASSWORD).statusCode()).isEqualTo(401);
            jdbc.update(
                    "UPDATE sys_user SET password_hash=?,enabled=FALSE WHERE id=?",
                    "{pbkdf2-sha256-120k}" + legacy,
                    USER_ID);
            assertThat(browser.login(INITIAL_PASSWORD).statusCode()).isEqualTo(401);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT password_hash FROM sys_user WHERE id=?",
                                    String.class,
                                    USER_ID))
                    .isEqualTo("{pbkdf2-sha256-120k}" + legacy);
        }
    }

    @Test
    void bootstrapIsSingleUseWithRolesAuditAndMandatoryPasswordChange() {
        String id =
                bootstrap.initialize(
                        new BootstrapAdminDTO("bootstrap_it", INITIAL_PASSWORD, "测试管理员"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",
                                Integer.class,
                                id))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT must_change_password FROM sys_user WHERE id=?",
                                Boolean.class,
                                id))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_oper_log WHERE action='auth:BOOTSTRAP'"
                                        + " AND success=TRUE",
                                Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                bootstrap.initialize(
                                        new BootstrapAdminDTO(
                                                "second_it", CHANGED_PASSWORD, "第二账号")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getError().code()).isEqualTo(1_002_000_006));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void passwordChangeInvalidatesOldAccessAndRefreshAndKeepsIdentifiersExact() throws Exception {
        seedUser(true);
        try (var browser = new Browser()) {
            var login = browser.login(INITIAL_PASSWORD);
            assertThat(login.statusCode()).isEqualTo(200);
            JsonNode data = json.readTree(login.body()).path("data");
            assertThat(data.path("profile").path("id").isString()).isTrue();
            assertThat(data.path("profile").path("id").asString())
                    .isEqualTo(Long.toString(USER_ID));
            assertThat(data.path("profile").path("mustChangePassword").asBoolean()).isTrue();
            String access = data.path("accessToken").asString();
            assertThat(browser.get("me", access).statusCode()).isEqualTo(200);
            String oldRefresh = browser.refreshCredential();
            var changed =
                    browser.post(
                            "password",
                            Map.of(
                                    "oldPassword",
                                    INITIAL_PASSWORD,
                                    "newPassword",
                                    CHANGED_PASSWORD),
                            access,
                            true);
            assertThat(changed.statusCode()).isEqualTo(200);
            String freshAccess =
                    json.readTree(changed.body()).path("data").path("accessToken").asString();
            assertThat(browser.get("me", access).statusCode()).isEqualTo(401);
            assertThat(browser.get("me", freshAccess).statusCode()).isEqualTo(200);
            String freshRefresh = browser.refreshCredential();
            browser.replaceRefreshCredential(oldRefresh);
            assertThat(browser.post("refresh", Map.of(), null, true).statusCode()).isEqualTo(401);
            browser.replaceRefreshCredential(freshRefresh);
            assertThat(browser.post("refresh", Map.of(), null, true).statusCode()).isEqualTo(200);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_audit WHERE action='LOGIN'",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT detail_text FROM sn_audit WHERE action='auth:PASSWORD_CHANGE'",
                                    String.class))
                    .isEqualTo("auth_it");
            var relogin = browser.login(CHANGED_PASSWORD);
            assertThat(relogin.statusCode()).isEqualTo(200);
            assertThat(
                            json.readTree(relogin.body())
                                    .path("data")
                                    .path("profile")
                                    .path("mustChangePassword")
                                    .asBoolean())
                    .isFalse();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT security_version FROM sys_user WHERE id=?",
                                Long.class,
                                USER_ID))
                .isEqualTo(2L);
        String changes =
                jdbc.queryForObject(
                        "SELECT CAST(changes AS CHAR) FROM sys_oper_log WHERE"
                                + " action='auth:PASSWORD_CHANGE'",
                        String.class);
        assertThat(changes).isEqualTo("{}");
    }

    @Test
    void cookieCsrfRefreshReplayAndLogoutAreEnforcedOverHttp() throws Exception {
        seedUser(false);
        try (var browser = new Browser()) {
            assertThat(
                            browser.post(
                                            "login",
                                            Map.of(
                                                    "username",
                                                    "auth_it",
                                                    "password",
                                                    INITIAL_PASSWORD),
                                            null,
                                            false)
                                    .statusCode())
                    .isEqualTo(403);
            var login = browser.login(INITIAL_PASSWORD);
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(
                            login.headers().allValues("set-cookie").stream()
                                    .anyMatch(
                                            value ->
                                                    value.contains("ADTEK-REFRESH=")
                                                            && value.contains("HttpOnly")
                                                            && value.contains("SameSite=Strict")))
                    .isTrue();
            String original = browser.refreshCredential();
            var refreshed = browser.post("refresh", Map.of(), null, true);
            assertThat(refreshed.statusCode()).isEqualTo(200);
            String refreshedAccess =
                    json.readTree(refreshed.body()).path("data").path("accessToken").asString();
            assertThat(browser.refreshCredential()).isNotEqualTo(original);
            browser.replaceRefreshCredential(original);
            assertThat(browser.post("refresh", Map.of(), null, true).statusCode()).isEqualTo(401);
            assertThat(browser.get("me", refreshedAccess).statusCode()).isEqualTo(401);
            var secondLogin = browser.login(INITIAL_PASSWORD);
            assertThat(secondLogin.statusCode()).isEqualTo(200);
            String secondAccess =
                    json.readTree(secondLogin.body()).path("data").path("accessToken").asString();
            assertThat(browser.post("logout", Map.of(), secondAccess, true).statusCode())
                    .isEqualTo(200);
            assertThat(browser.get("me", secondAccess).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void accountDisableAndPermissionShrinkAreObservedWithoutWaitingForJwtExpiry() throws Exception {
        seedUser(false);
        try (var browser = new Browser()) {
            var login = browser.login(INITIAL_PASSWORD);
            assertThat(login.statusCode()).isEqualTo(200);
            String access = json.readTree(login.body()).path("data").path("accessToken").asString();
            jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", USER_ID);
            var reduced = browser.get("me", access);
            assertThat(reduced.statusCode()).isEqualTo(200);
            assertThat(json.readTree(reduced.body()).path("data").path("permissions").size())
                    .isZero();
            jdbc.update(
                    "UPDATE sys_user SET enabled=FALSE,security_version=security_version+1 WHERE"
                            + " id=?",
                    USER_ID);
            assertThat(browser.get("me", access).statusCode()).isEqualTo(401);
            assertThat(browser.post("refresh", Map.of(), null, true).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void languageDiffUsesDatabaseBeforeAndAfterAndRejectsUnknownLanguage() throws Exception {
        seedUser(false);
        try (var browser = new Browser()) {
            var login = browser.login(INITIAL_PASSWORD);
            assertThat(login.statusCode()).isEqualTo(200);
            String access = json.readTree(login.body()).path("data").path("accessToken").asString();
            assertThat(
                            browser.post("language", Map.of("language", "vi"), access, true)
                                    .statusCode())
                    .isEqualTo(200);
            assertThat(
                            browser.post("language", Map.of("language", "ar"), access, true)
                                    .statusCode())
                    .isEqualTo(400);
        }
        var diff =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT CAST(changes AS CHAR) FROM sys_oper_log WHERE"
                                        + " action='auth:LANGUAGE_CHANGE'",
                                String.class));
        assertThat(diff.path("language").path("before").asString()).isEqualTo("zh-CN");
        assertThat(diff.path("language").path("after").asString()).isEqualTo("vi");
    }

    @Test
    void subjectBudgetStillLimitsSuccessfulLoginsIndependentlyOfFailureLock() throws Exception {
        seedUser(false);
        try (var browser = new Browser()) {
            for (int index = 0; index < 8; index++) {
                assertThat(browser.login(INITIAL_PASSWORD).statusCode()).isEqualTo(200);
            }
            var limited = browser.login(INITIAL_PASSWORD);
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow()))
                    .isBetween(1, 60);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_oper_log WHERE action='auth:LOGIN' AND"
                                        + " success=TRUE",
                                Integer.class))
                .isEqualTo(8);
        assertThat(
                        jdbc.queryForList(
                                "SELECT CAST(changes AS CHAR) FROM sys_oper_log", String.class))
                .allSatisfy(
                        value ->
                                assertThat(value)
                                        .doesNotContain(INITIAL_PASSWORD, "password", "token"));
    }

    @Test
    void fifthFailureLocksEvenCorrectPasswordAndAuditsUnknownAccountsWithoutCredentials()
            throws Exception {
        // 匿名不存在的账号和真实账号使用相同的失败预算；锁在成功匹配密码前检查。
        try (var browser = new Browser()) {
            for (int i = 0; i < 5; i++)
                assertThat(browser.login(INITIAL_PASSWORD).statusCode()).isEqualTo(401);
            seedUser(false);
            var response = browser.login(INITIAL_PASSWORD);
            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(json.readTree(response.body()).path("code").asInt())
                    .isEqualTo(1_002_000_017);
            assertThat(json.readTree(response.body()).path("params").path("minutes").asString())
                    .isEqualTo("15");
            assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()))
                    .isBetween(890, 900);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_oper_log WHERE action='auth:LOGIN' AND success=FALSE",
                                Integer.class))
                .isEqualTo(6);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_oper_log WHERE JSON_EXTRACT(changes,'$.lockStarted')=TRUE",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT CAST(changes AS CHAR) FROM sys_oper_log", String.class))
                .allSatisfy(
                        value ->
                                assertThat(value)
                                        .doesNotContain(
                                                INITIAL_PASSWORD, "auth_it", "password", "token"));
    }

    @Test
    void successfulLoginClearsFailuresButCannotClearAnActiveLock() throws Exception {
        seedUser(false);
        for (int i = 0; i < 4; i++) loginGuard.failed("auth_it");
        try (var browser = new Browser()) {
            assertThat(browser.login(INITIAL_PASSWORD).statusCode()).isEqualTo(200);
            for (int i = 0; i < 4; i++) assertThat(loginGuard.failed("auth_it")).isFalse();
            assertThat(loginGuard.failed("auth_it")).isTrue();
            assertThatThrownBy(() -> loginGuard.succeeded("auth_it"))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            error -> assertThat(error.getError().code()).isEqualTo(1_002_000_017));
            assertThat(browser.login(INITIAL_PASSWORD).statusCode()).isEqualTo(429);
        }
    }

    @Test
    void independentGuardsShareAtomicFailureBudgetAndLockExpires() throws Exception {
        var other =
                new com.adtek.system.service.support.LoginFailureGuard(
                        redis, new com.adtek.system.service.support.LoginGuardSettings(5, 900));
        var started = new java.util.concurrent.atomic.AtomicInteger();
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 20; i++) {
                var guard = i % 2 == 0 ? loginGuard : other;
                futures.add(
                        pool.submit(
                                () -> {
                                    try {
                                        if (guard.failed("parallel_it")) started.incrementAndGet();
                                    } catch (BusinessException error) {
                                        assertThat(error.getError().code())
                                                .isEqualTo(1_002_000_017);
                                        rejected.incrementAndGet();
                                    }
                                }));
            }
            for (var future : futures) future.get();
        }
        assertThat(started.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(15);
        other.check("separate_it");
        var shortGuard =
                new com.adtek.system.service.support.LoginFailureGuard(
                        redis, new com.adtek.system.service.support.LoginGuardSettings(1, 1));
        assertThat(shortGuard.failed("expires_it")).isTrue();
        assertThatThrownBy(() -> shortGuard.check("expires_it"))
                .isInstanceOf(BusinessException.class);
        Thread.sleep(1100);
        shortGuard.check("expires_it");
        assertThat(shortGuard.failed("expires_it")).isTrue();
    }

    @Test
    void malformedRequestsCannotBypassSourceBudgetUsingUntrustedForwardingHeaders()
            throws Exception {
        try (var browser = new Browser()) {
            for (int index = 0; index < 100; index++) {
                assertThat(browser.post("login", Map.of(), null, false).statusCode())
                        .isEqualTo(403);
            }
            assertThat(browser.post("login", Map.of(), null, false).statusCode()).isEqualTo(429);
        }
    }

    @Test
    void allFiveSqlScopeKindsRespectEmptyDepartmentAndDisabledDescendants() {
        seedUser(false);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", USER_ID);
        jdbc.update(
                "INSERT INTO sys_dept(id,parent_id,dept_name,enabled) VALUES"
                        + " (1001,1,'子部门',TRUE),(1002,1001,'孙部门',TRUE),(1003,1,'停用部门',FALSE)");
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope,factory_scope) VALUES"
                        + " (1100,'IT_SCOPE','范围测试',2,1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1100,112)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,1100)", USER_ID);
        jdbc.update("INSERT INTO sys_role_dept(role_id,dept_id) VALUES (1100,1002)");
        assertThat(
                        authorizations
                                .loadCurrent(USER_ID)
                                .orElseThrow()
                                .grants()
                                .get("system:user:update")
                                .departments()
                                .departmentIds())
                .containsExactly(1002L);
        jdbc.update("UPDATE sys_role SET data_scope=3 WHERE id=1100");
        assertThat(
                        authorizations
                                .loadCurrent(USER_ID)
                                .orElseThrow()
                                .grants()
                                .get("system:user:update")
                                .departments()
                                .departmentIds())
                .containsExactly(1L);
        jdbc.update("UPDATE sys_role SET data_scope=4 WHERE id=1100");
        assertThat(
                        authorizations
                                .loadCurrent(USER_ID)
                                .orElseThrow()
                                .grants()
                                .get("system:user:update")
                                .departments()
                                .departmentIds())
                .containsExactlyInAnyOrder(1L, 1001L, 1002L);
        jdbc.update("UPDATE sys_user SET dept_id=NULL WHERE id=?", USER_ID);
        var empty =
                authorizations
                        .loadCurrent(USER_ID)
                        .orElseThrow()
                        .grants()
                        .get("system:user:update")
                        .departments();
        assertThat(empty.departmentIds()).isEmpty();
        assertThat(empty.all()).isFalse();
        assertThat(empty.self()).isFalse();
        jdbc.update("UPDATE sys_role SET data_scope=5 WHERE id=1100");
        assertThat(
                        authorizations
                                .loadCurrent(USER_ID)
                                .orElseThrow()
                                .grants()
                                .get("system:user:update")
                                .departments()
                                .self())
                .isTrue();
        jdbc.update("UPDATE sys_role SET data_scope=1 WHERE id=1100");
        assertThat(
                        authorizations
                                .loadCurrent(USER_ID)
                                .orElseThrow()
                                .grants()
                                .get("system:user:update")
                                .departments()
                                .all())
                .isTrue();
    }

    @Test
    void sqlRoleCompositionDoesNotPromoteLocalWriteWithGlobalReadRole() {
        seedUser(false);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", USER_ID);
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope,factory_scope) VALUES"
                        + " (1000,'IT_READ','全局读',1,2),(1001,'IT_WRITE','本部门写',3,1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1000,110),(1001,112)");
        jdbc.update(
                "INSERT INTO sys_user_role(user_id,role_id) VALUES (?,1000),(?,1001)",
                USER_ID,
                USER_ID);
        jdbc.update("INSERT INTO sn_user_factory(user_id,factory_id) VALUES (?,21)", USER_ID);
        var actor = authorizations.loadCurrent(USER_ID).orElseThrow();
        var read = actor.grants().get("system:user:list");
        var write = actor.grants().get("system:user:update");
        assertThat(read.departments().all()).isTrue();
        assertThat(read.allFactories()).isTrue();
        assertThat(write.departments().all()).isFalse();
        assertThat(write.departments().departmentIds()).containsExactly(1L);
        assertThat(write.permitsFactory(21L)).isTrue();
        assertThat(write.permitsFactory(22L)).isFalse();
    }

    private void seedUser(boolean mustChange) {
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,enabled,must_change_password,security_version,language)"
                        + " VALUES (?,'auth_it',?,'集成测试',1,TRUE,?,1,'zh-CN')",
                USER_ID,
                passwords.encode(INITIAL_PASSWORD),
                mustChange);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,1)", USER_ID);
    }

    @Test
    void allFiveDataScopesExecuteAgainstMysqlIncludingPaginationCounts() throws Exception {
        seedUser(false);
        jdbc.update(
                "INSERT INTO sys_dept(id,parent_id,dept_name,enabled) VALUES"
                        + " (1001,1,'下级',TRUE),(1002,1001,'孙级',TRUE),(1003,1,'停用',FALSE)");
        jdbc.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name,dept_id) VALUES"
                        + " (51,'scope_one','unused','子',1001),(52,'scope_two','unused','孙',1002),(53,'scope_three','unused','停',1003),(54,'scope_four','unused','空',NULL)");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", USER_ID);
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES"
                        + " (1100,'IT_SQL_SCOPES','真实范围',1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1100,110)");
        jdbc.update("INSERT INTO sys_role_dept(role_id,dept_id) VALUES (1100,1002)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,1100)", USER_ID);
        try (var browser = new Browser()) {
            String token =
                    json.readTree(browser.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            var expected = Map.of(1, "5", 2, "1", 3, "1", 4, "3", 5, "1");
            for (int scope = 1; scope <= 5; scope++) {
                jdbc.update("UPDATE sys_role SET data_scope=? WHERE id=1100", scope);
                var response = browser.get("/users?size=1", token);
                assertThat(response.statusCode()).as("data_scope=" + scope).isEqualTo(200);
                var data = json.readTree(response.body()).path("data");
                assertThat(data.path("total").asString())
                        .as("data_scope=" + scope)
                        .isEqualTo(expected.get(scope));
                assertThat(data.path("items").size()).isEqualTo(1);
                if (scope == 2) {
                    assertThat(data.path("items").get(0).path("id").asString()).isEqualTo("52");
                }
                if (scope == 5) {
                    assertThat(data.path("items").get(0).path("id").asString())
                            .isEqualTo(Long.toString(USER_ID));
                }
            }
            assertThat(browser.get("/users?page=abc", token).statusCode()).isEqualTo(400);
            assertThat(browser.get("/users?size=101", token).statusCode()).isEqualTo(400);
        }
    }

    @Test
    void roleAssignmentCannotGrantPermissionsActorDoesNotHaveAndRollsBackCreatedUser()
            throws Exception {
        seedUser(false);
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (55,'grant_it',?,'授予测试',1,FALSE)",
                passwords.encode(INITIAL_PASSWORD));
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES"
                        + " (1100,'IT_GRANT','有限授予',1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1100,111),(1100,199)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (55,1100)");
        try (var browser = new Browser()) {
            String token =
                    json.readTree(
                                    browser.post(
                                                    "login",
                                                    Map.of(
                                                            "username",
                                                            "grant_it",
                                                            "password",
                                                            INITIAL_PASSWORD),
                                                    null,
                                                    true)
                                            .body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            var response =
                    browser.post(
                            "/users",
                            Map.of(
                                    "username",
                                    "escalated_it",
                                    "displayName",
                                    "禁止提权",
                                    "deptId",
                                    "1",
                                    "roleIds",
                                    java.util.List.of("1"),
                                    "password",
                                    INITIAL_PASSWORD),
                            token,
                            true);
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(json.readTree(response.body()).path("code").asInt())
                    .isEqualTo(1_002_000_011);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user WHERE username='escalated_it'",
                                    Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_role WHERE user_id NOT IN"
                                            + " (SELECT id FROM sys_user)",
                                    Integer.class))
                    .isZero();
        }
    }

    @Test
    void userLifecycleHasBoundPaginationDiffUniqueNameAndVersionConflicts() throws Exception {
        seedUser(false);
        // S3工厂操作员已有真实SN权限；管理该角色须同时持SN总部能力，不能只凭平台角色越权委派。
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,2)", USER_ID);
        try (var browser = new Browser()) {
            String access =
                    json.readTree(browser.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            var create =
                    browser.post(
                            "/users",
                            Map.of(
                                    "username",
                                    "managed_it",
                                    "displayName",
                                    "用户样板",
                                    "deptId",
                                    "1",
                                    "roleIds",
                                    java.util.List.of("3"),
                                    "password",
                                    INITIAL_PASSWORD),
                            access,
                            true);
            assertThat(create.statusCode()).as(create.body()).isEqualTo(200);
            var user = json.readTree(create.body()).path("data");
            String id = user.path("id").asString();
            assertThat(Long.parseLong(id)).isGreaterThan(USER_ID);
            assertThat(user.has("passwordHash")).isFalse();
            var duplicate =
                    browser.post(
                            "/users",
                            Map.of(
                                    "username",
                                    "managed_it",
                                    "displayName",
                                    "重复",
                                    "deptId",
                                    "1",
                                    "roleIds",
                                    java.util.List.of(),
                                    "password",
                                    INITIAL_PASSWORD),
                            access,
                            true);
            assertThat(duplicate.statusCode()).isEqualTo(409);
            var page = browser.get("/users?keyword=managed&page=1&size=1", access);
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            assertThat(json.readTree(page.body()).path("data").path("total").asString())
                    .isEqualTo("1");
            var update =
                    Map.of(
                            "displayName",
                            "新姓名",
                            "deptId",
                            "1",
                            "roleIds",
                            java.util.List.of("3"),
                            "version",
                            "1");
            assertThat(browser.post("/users/" + id + "/update", update, access, true).statusCode())
                    .isEqualTo(200);
            assertThat(browser.post("/users/" + id + "/update", update, access, true).statusCode())
                    .isEqualTo(409);
            assertThat(
                            browser.post(
                                            "/users/" + id + "/status",
                                            Map.of("enabled", false, "version", "2"),
                                            access,
                                            true)
                                    .statusCode())
                    .isEqualTo(200);
            assertThat(
                            browser.post(
                                            "/users/" + id + "/status",
                                            Map.of("enabled", true, "version", "3"),
                                            access,
                                            true)
                                    .statusCode())
                    .isEqualTo(200);
            String diff =
                    jdbc.queryForObject(
                            "SELECT CAST(changes AS CHAR) FROM sys_oper_log WHERE"
                                    + " action='system:user:update' AND success=TRUE",
                            String.class);
            assertThat(json.readTree(diff).path("displayName").path("before").asString())
                    .isEqualTo("用户样板");
            assertThat(json.readTree(diff).size()).isEqualTo(1);
            assertThat(browser.get("/menus/current", access).statusCode()).isEqualTo(200);
            assertThat(browser.get("/users/options?operation=add", access).statusCode())
                    .isEqualTo(200);
        }
        assertThat(
                        jdbc.queryForList(
                                "SELECT CAST(changes AS CHAR) FROM sys_oper_log", String.class))
                .allSatisfy(
                        value ->
                                assertThat(value).doesNotContain(INITIAL_PASSWORD, "passwordHash"));
    }

    @Test
    void resetRevokesTargetAccessAndRefreshAndForcesPasswordChange() throws Exception {
        seedUser(false);
        try (var admin = new Browser();
                var target = new Browser()) {
            String adminToken =
                    json.readTree(admin.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            var created =
                    admin.post(
                            "/users",
                            Map.of(
                                    "username",
                                    "reset_it",
                                    "displayName",
                                    "重置目标",
                                    "deptId",
                                    "1",
                                    "roleIds",
                                    java.util.List.of(),
                                    "password",
                                    INITIAL_PASSWORD),
                            adminToken,
                            true);
            assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
            String id = json.readTree(created.body()).path("data").path("id").asString();
            String targetToken =
                    json.readTree(
                                    target.post(
                                                    "login",
                                                    Map.of(
                                                            "username",
                                                            "reset_it",
                                                            "password",
                                                            INITIAL_PASSWORD),
                                                    null,
                                                    true)
                                            .body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            assertThat(
                            admin.post(
                                            "/users/" + id + "/password",
                                            Map.of("password", CHANGED_PASSWORD, "version", "1"),
                                            adminToken,
                                            true)
                                    .statusCode())
                    .isEqualTo(200);
            assertThat(target.get("me", targetToken).statusCode()).isEqualTo(401);
            assertThat(target.post("refresh", Map.of(), null, true).statusCode()).isEqualTo(401);
            var login =
                    target.post(
                            "login",
                            Map.of("username", "reset_it", "password", CHANGED_PASSWORD),
                            null,
                            true);
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(
                            json.readTree(login.body())
                                    .path("data")
                                    .path("profile")
                                    .path("mustChangePassword")
                                    .asBoolean())
                    .isTrue();
        }
    }

    @Test
    void userSqlFiltersReadAndWriteSeparatelyAndEmptyCustomScopeReturnsEmptyPage()
            throws Exception {
        seedUser(false);
        jdbc.update("INSERT INTO sys_dept(id,dept_name) VALUES (1001,'范围之外')");
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (44,'outside_it',?,'外部',1001,FALSE)",
                passwords.encode(INITIAL_PASSWORD));
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", USER_ID);
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES"
                        + " (1000,'IT_READ','全部读',1),(1001,'IT_WRITE','本部门写',3)");
        jdbc.update(
                "INSERT INTO sys_role_menu(role_id,menu_id) VALUES"
                        + " (1000,110),(1001,112),(1001,113)");
        jdbc.update(
                "INSERT INTO sys_user_role(user_id,role_id) VALUES (?,1000),(?,1001)",
                USER_ID,
                USER_ID);
        try (var browser = new Browser()) {
            String access =
                    json.readTree(browser.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            assertThat(
                            json.readTree(browser.get("/users?size=1", access).body())
                                    .path("data")
                                    .path("total")
                                    .asString())
                    .isEqualTo("2");
            assertThat(
                            browser.post(
                                            "/users/44/password",
                                            Map.of("password", CHANGED_PASSWORD, "version", "1"),
                                            access,
                                            true)
                                    .statusCode())
                    .isEqualTo(404);
            jdbc.update("UPDATE sys_role SET data_scope=2 WHERE id=1000");
            var empty = browser.get("/users?size=1", access);
            assertThat(empty.statusCode()).as(empty.body()).isEqualTo(200);
            assertThat(json.readTree(empty.body()).path("data").path("total").asString())
                    .isEqualTo("0");
            assertThat(json.readTree(empty.body()).path("data").path("items").size()).isZero();
        }
    }

    @Test
    void ordinaryManagerCannotResetPlatformAdminAndSelfOrLastAdminProtectionIsTransactional()
            throws Exception {
        seedUser(false);
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (45,'limited_it',?,'普通管理员',1,FALSE)",
                passwords.encode(INITIAL_PASSWORD));
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES"
                        + " (1000,'IT_LIMITED','普通管理',1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1000,113)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (45,1000)");
        try (var limited = new Browser();
                var admin = new Browser()) {
            String limitedToken =
                    json.readTree(
                                    limited.post(
                                                    "login",
                                                    Map.of(
                                                            "username",
                                                            "limited_it",
                                                            "password",
                                                            INITIAL_PASSWORD),
                                                    null,
                                                    true)
                                            .body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            var rejection =
                    limited.post(
                            "/users/" + USER_ID + "/password",
                            Map.of("password", CHANGED_PASSWORD, "version", "1"),
                            limitedToken,
                            true);
            assertThat(rejection.statusCode()).isEqualTo(403);
            String token =
                    json.readTree(admin.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            assertThat(
                            admin.post(
                                            "/users/" + USER_ID + "/status",
                                            Map.of("enabled", false, "version", "1"),
                                            token,
                                            true)
                                    .statusCode())
                    .isEqualTo(409);
            var shrink =
                    admin.post(
                            "/users/" + USER_ID + "/update",
                            Map.of(
                                    "displayName",
                                    "不能提交",
                                    "deptId",
                                    "1",
                                    "roleIds",
                                    java.util.List.of(),
                                    "version",
                                    "1"),
                            token,
                            true);
            assertThat(shrink.statusCode()).as(shrink.body()).isEqualTo(409);
            assertThat(json.readTree(shrink.body()).path("code").asInt()).isEqualTo(1_002_000_010);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT display_name FROM sys_user WHERE id=?",
                                    String.class,
                                    USER_ID))
                    .isEqualTo("集成测试");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT security_version FROM sys_user WHERE id=?",
                                    Long.class,
                                    USER_ID))
                    .isEqualTo(1L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE"
                                            + " action='system:user:update' AND success=FALSE",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void auditStorageFailureRollsBackAccountCreation() throws Exception {
        seedUser(false);
        try (var browser = new Browser()) {
            String token =
                    json.readTree(browser.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_it_audit_failure CHECK"
                            + " (NOT(action='system:user:add' AND success=TRUE))");
            try {
                var response =
                        browser.post(
                                "/users",
                                Map.of(
                                        "username",
                                        "rollback_it",
                                        "displayName",
                                        "回滚",
                                        "deptId",
                                        "1",
                                        "roleIds",
                                        java.util.List.of(),
                                        "password",
                                        INITIAL_PASSWORD),
                                token,
                                true);
                assertThat(response.statusCode()).isEqualTo(500);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user WHERE"
                                                + " username='rollback_it'",
                                        Integer.class))
                        .isZero();
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_it_audit_failure");
            }
        }
    }

    @Test
    void concurrentSelfDemotionsCannotRemoveBothAdministrators() throws Exception {
        seedUser(false);
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (46,'second_admin_it',?,'第二管理员',1,FALSE)",
                passwords.encode(INITIAL_PASSWORD));
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (46,1)");
        try (var first = new Browser();
                var second = new Browser();
                var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            String one =
                    json.readTree(first.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            String two =
                    json.readTree(
                                    second.post(
                                                    "login",
                                                    Map.of(
                                                            "username",
                                                            "second_admin_it",
                                                            "password",
                                                            INITIAL_PASSWORD),
                                                    null,
                                                    true)
                                            .body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            var ready = new java.util.concurrent.CountDownLatch(1);
            var command =
                    Map.of(
                            "displayName",
                            "缩权",
                            "deptId",
                            "1",
                            "roleIds",
                            java.util.List.of(),
                            "version",
                            "1");
            var left =
                    executor.submit(
                            () -> {
                                ready.await();
                                return first.post(
                                                "/users/" + USER_ID + "/update", command, one, true)
                                        .statusCode();
                            });
            var right =
                    executor.submit(
                            () -> {
                                ready.await();
                                return second.post("/users/46/update", command, two, true)
                                        .statusCode();
                            });
            ready.countDown();
            assertThat(java.util.List.of(left.get(), right.get()))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_role WHERE role_id=1",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void accountDirectoryFiltersBeforePaginationAndReportsOnlyActualSuccessfulLogin()
            throws Exception {
        seedUser(false);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,2)", USER_ID);
        jdbc.update(
                "INSERT INTO sn_factory(id,code,name,enabled,source) VALUES (9039001,'DIR_A','目录工厂',TRUE,'MANUAL')");
        String hash = passwords.encode(INITIAL_PASSWORD);
        for (int n = 0; n < 24; n++) {
            long id = USER_ID + n + 1;
            jdbc.update(
                    """
                    INSERT INTO sys_user(id,principal_type,username,password_hash,display_name,dept_id,
                      enabled,must_change_password,security_version,create_by,create_time)
                    VALUES (?,'USER',?,?,?,1,?,FALSE,1,?,'2026-10-09 00:00:00')
                    """,
                    id,
                    "dir_" + String.format("%02d", n),
                    hash,
                    "目录成员" + n,
                    n != 23,
                    USER_ID);
            jdbc.update(
                    "INSERT INTO sys_user_role(user_id,role_id) VALUES (?,?)", id, n < 22 ? 3 : 4);
            if (n < 21)
                jdbc.update(
                        "INSERT INTO sn_user_factory(user_id,factory_id) VALUES (?,9039001)", id);
        }
        try (var browser = new Browser()) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_login_at FROM sys_user WHERE id=?",
                                    java.sql.Timestamp.class,
                                    USER_ID))
                    .isNull();
            String access =
                    json.readTree(browser.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            var logged =
                    jdbc.queryForObject(
                            "SELECT last_login_at FROM sys_user WHERE id=?",
                            java.sql.Timestamp.class,
                            USER_ID);
            assertThat(logged).isNotNull();
            assertThat(browser.login(CHANGED_PASSWORD).statusCode()).isEqualTo(401);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_login_at FROM sys_user WHERE id=?",
                                    java.sql.Timestamp.class,
                                    USER_ID))
                    .isEqualTo(logged);
            var response =
                    browser.get(
                            "/users?keyword=dir_&roleCodes=FACTORY_OPERATOR&factoryId=9039001&page=2&size=20",
                            access);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var data = json.readTree(response.body()).path("data");
            assertThat(data.path("total").asString()).isEqualTo("21");
            assertThat(data.path("items").size()).isEqualTo(1);
            var item = data.path("items").get(0);
            assertThat(item.path("username").asString()).isEqualTo("dir_20");
            assertThat(item.path("createdByName").asString()).isEqualTo("auth_it");
            assertThat(item.path("createdAt").asString()).isEqualTo("2026-10-09T00:00:00Z");
            assertThat(item.path("lastLoginAt").isNull()).isTrue();
            assertThat(item.path("factories").get(0).path("code").asString()).isEqualTo("DIR_A");
            assertThat(item.has("passwordHash")).isFalse();
            var tail =
                    json.readTree(browser.get("/users?keyword=ir_&page=1&size=20", access).body())
                            .path("data");
            assertThat(tail.path("total").asString()).isEqualTo("0");
            var queryOnly =
                    json.readTree(
                                    browser.get(
                                                    "/users?keyword=dir_&excludedRoleCodes=FACTORY_OPERATOR&enabled=false",
                                                    access)
                                            .body())
                            .path("data");
            assertThat(queryOnly.path("total").asString()).isEqualTo("1");
            assertThat(queryOnly.path("items").get(0).path("username").asString())
                    .isEqualTo("dir_23");
            assertThat(browser.get("/users?roleCodes=%27OR1", access).statusCode()).isEqualTo(400);
        }
    }

    @Test
    void snAccountValidationPreservesPrototypeOrderAndNeverCreatesRejectedAccounts()
            throws Exception {
        seedUser(false);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,2)", USER_ID);
        try (var browser = new Browser()) {
            String token =
                    json.readTree(browser.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            var cases =
                    java.util.List.of(
                            Map.of(
                                    "username",
                                    "!",
                                    "displayName",
                                    "",
                                    "role",
                                    "factory_operator",
                                    "password",
                                    ""),
                            Map.of(
                                    "username",
                                    "valid",
                                    "displayName",
                                    " ",
                                    "role",
                                    "factory_operator",
                                    "password",
                                    ""),
                            Map.of(
                                    "username",
                                    "valid",
                                    "displayName",
                                    "姓名",
                                    "role",
                                    "invalid",
                                    "password",
                                    ""),
                            Map.of(
                                    "username",
                                    "valid",
                                    "displayName",
                                    "姓名",
                                    "role",
                                    "factory_operator",
                                    "password",
                                    ""),
                            Map.of(
                                    "username",
                                    "valid",
                                    "displayName",
                                    "姓名",
                                    "role",
                                    "admin",
                                    "factoryId",
                                    "999999",
                                    "password",
                                    ""),
                            Map.of(
                                    "username",
                                    "valid",
                                    "displayName",
                                    "姓名",
                                    "role",
                                    "query",
                                    "password",
                                    ""));
            var reasons =
                    java.util.List.of(
                            "USER_EMP_NO_INVALID",
                            "USER_NAME_REQUIRED",
                            "USER_ROLE_INVALID",
                            "USER_FACTORY_REQUIRED",
                            "USER_ADMIN_NO_FACTORY",
                            "USER_PASSWORD_WEAK");
            for (int i = 0; i < cases.size(); i++) {
                var response = browser.post("/sn/users", cases.get(i), token, true);
                assertThat(response.statusCode()).isEqualTo(400);
                var data = json.readTree(response.body());
                assertThat(data.path("code").asInt()).isEqualTo(1_004_000_030);
                assertThat(data.path("params").path("reason").asString()).isEqualTo(reasons.get(i));
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void snAccountCreatesAndEditsEachRoleWithServerManagedDepartmentAndVersion() throws Exception {
        seedUser(false);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,2)", USER_ID);
        jdbc.update(
                "INSERT INTO sn_factory(id,code,name,enabled,source) VALUES (9040001,'ACC40','账号测试工厂',TRUE,'MANUAL')");
        try (var browser = new Browser()) {
            String token =
                    json.readTree(browser.login(INITIAL_PASSWORD).body())
                            .path("data")
                            .path("accessToken")
                            .asString();
            var create =
                    Map.of(
                            "username",
                            " account40 ",
                            "displayName",
                            " 姓名 ",
                            "role",
                            "factory_operator",
                            "factoryId",
                            "9040001",
                            "password",
                            INITIAL_PASSWORD);
            var response = browser.post("/sn/users", create, token, true);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            String id = json.readTree(response.body()).path("data").path("id").asString();
            var row = jdbc.queryForMap("SELECT * FROM sys_user WHERE id=?", id);
            assertThat(row.get("username")).isEqualTo("account40");
            assertThat(row.get("display_name")).isEqualTo("姓名");
            assertThat(((Number) row.get("dept_id")).longValue()).isEqualTo(1L);
            assertThat(row.get("must_change_password")).isEqualTo(true);
            assertThat(passwords.matches(INITIAL_PASSWORD, (String) row.get("password_hash")))
                    .isTrue();
            assertThat(
                            json.readTree(browser.post("/sn/users", create, token, true).body())
                                    .path("params")
                                    .path("reason")
                                    .asString())
                    .isEqualTo("USER_EMP_NO_EXISTS");
            for (String role : java.util.List.of("query", "admin", "factory_operator")) {
                long version =
                        jdbc.queryForObject(
                                "SELECT security_version FROM sys_user WHERE id=?", Long.class, id);
                var update = new java.util.HashMap<String, String>();
                update.put("displayName", "名".repeat(64));
                update.put("role", role);
                update.put("version", Long.toString(version));
                if (!role.equals("admin")) update.put("factoryId", "9040001");
                var changed = browser.post("/sn/users/" + id + "/update", update, token, true);
                assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT display_name FROM sys_user WHERE id=?",
                                        String.class,
                                        id))
                        .isEqualTo("名".repeat(64));
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sn_user_factory WHERE user_id=?",
                                        Integer.class,
                                        id))
                        .isEqualTo(role.equals("admin") ? 0 : 1);
                var roles =
                        jdbc.queryForList(
                                "SELECT r.role_code FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? ORDER BY r.role_code",
                                String.class,
                                id);
                assertThat(roles)
                        .containsExactlyElementsOf(
                                role.equals("admin")
                                        ? java.util.List.of("PLATFORM_ADMIN", "SN_HQ")
                                        : java.util.List.of(
                                                role.equals("query")
                                                        ? "SN_QUERY_FACTORY"
                                                        : "FACTORY_OPERATOR"));
                assertThat(
                                browser.post("/sn/users/" + id + "/update", update, token, true)
                                        .statusCode())
                        .isEqualTo(409);
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT password_hash FROM sys_user WHERE id=?",
                                    String.class,
                                    id))
                    .isEqualTo(row.get("password_hash"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE object_id=? AND success=TRUE",
                                    Integer.class,
                                    id))
                    .isEqualTo(4);
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (?,1)", id);
            var extended =
                    browser.post(
                            "/sn/users/" + id + "/update",
                            Map.of("displayName", "overwrite", "role", "query", "version", "4"),
                            token,
                            true);
            assertThat(extended.statusCode()).isEqualTo(409);
            assertThat(json.readTree(extended.body()).path("code").asInt())
                    .isEqualTo(1_004_000_031);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(2);
        }
    }

    private final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        private String csrfToken;
        private String csrfHeader;

        private URI uri(String path) {
            return URI.create(
                    "http://127.0.0.1:"
                            + port
                            + (path.startsWith("/sn/")
                                    ? "/api/v1" + path
                                    : "/api/v1/system"
                                            + (path.startsWith("/") ? path : "/auth/" + path)));
        }

        HttpResponse<String> login(String password) throws Exception {
            return post("login", Map.of("username", "auth_it", "password", password), null, true);
        }

        HttpResponse<String> get(String path, String access) throws Exception {
            var request = HttpRequest.newBuilder(uri(path));
            if (access != null) {
                request.header("Authorization", "Bearer " + access);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body, String access, boolean withCsrf)
                throws Exception {
            // 标准 Security 认证策略可能清除旧 CSRF Cookie；每次写入前重新取得同源令牌。
            if (withCsrf) {
                var result = get("csrf", null);
                assertThat(result.statusCode()).isEqualTo(200);
                var data = json.readTree(result.body()).path("data");
                csrfToken = data.path("token").asString();
                csrfHeader = data.path("headerName").asString();
            }
            var request =
                    HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header(
                                    "X-Forwarded-For",
                                    "198.51.100."
                                            + java.util.concurrent.ThreadLocalRandom.current()
                                                    .nextInt(1, 250));
            if (access != null) {
                request.header("Authorization", "Bearer " + access);
            }
            if (withCsrf) {
                request.header(csrfHeader, csrfToken);
            }
            return client.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        String refreshCredential() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("ADTEK-REFRESH"))
                    .findFirst()
                    .orElseThrow()
                    .getValue();
        }

        void replaceRefreshCredential(String value) {
            cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("ADTEK-REFRESH"))
                    .forEach(cookie -> cookie.setValue(value));
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
