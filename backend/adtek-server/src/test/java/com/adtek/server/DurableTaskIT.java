package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.*;
import com.adtek.framework.task.*;
import com.adtek.framework.task.mapper.*;
import com.adtek.framework.task.service.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 真实HTTP、MySQL竞争/事务、Redis和本机文件；工作器手动驱动以精确注入领取/发布/提交故障。 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"adtek.tasks.export-rows=20", "adtek.tasks.file-bytes=4096"})
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DurableTaskIT {
    static final String SECRET = HexFormat.of().formatHex(new SecureRandom().generateSeed(32)),
            PASSWORD = "Task9!" + SECRET.substring(0, 24);

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_tasks_it")
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
    @Autowired TaskExecutionService worker;
    @Autowired DurableTaskMapper tasks;
    @Autowired TaskSettings settings;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TaskFileService downloads;
    @Autowired AuthorizationPort authorization;
    @Autowired TaskProgressService progress;
    @LocalServerPort int port;

    @BeforeEach
    void fixtures() {
        for (String table :
                List.of(
                        "sys_audit_export_row",
                        "sys_file",
                        "sys_task_progress",
                        "sys_task",
                        "sys_operation",
                        "sys_user_role",
                        "sn_user_factory",
                        "sys_user",
                        "sys_oper_log")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id>=1000");
        jdbc.update("DELETE FROM sys_role WHERE id>=1000");
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope)"
                        + " VALUES(1000,'EXPORTER','导出员',1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1000,150),(1000,151)");
        for (long id : List.of(10L, 11L)) {
            jdbc.update(
                    "INSERT INTO"
                            + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                            + " VALUES(?,?,?,?,1,FALSE)",
                    id,
                    "export_" + id,
                    passwords.encode(PASSWORD),
                    "导出测试");
            jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1000)", id);
        }
        jdbc.update(
                "INSERT INTO"
                        + " sys_oper_log(id,user_id,actor_dept_id,actor_username,module_name,action,success,changes,create_time)"
                        + " VALUES(9007199254741111,10,1,'export_10','=FORMULA','fixture:export',TRUE,'{}','2026-01-02"
                        + " 00:00:00')");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Map<String, Object> input(String operation) {
        return new HashMap<>(
                Map.of(
                        "operationId",
                        operation,
                        "from",
                        "2026-01-01T00:00:00Z",
                        "to",
                        "2026-01-03T00:00:00Z",
                        "action",
                        "fixture:export"));
    }

    private String submit(Browser browser, String operation) throws Exception {
        var response = browser.post("/system/audit-exports", input(operation));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return body(response).path("taskId").asString();
    }

    private void run(String expected) throws Exception {
        var lease = worker.claim(UUID.randomUUID().toString());
        assertThat(lease).isNotNull();
        assertThat(lease.id()).isEqualTo(expected);
        worker.execute(lease);
    }

    @Test
    void concurrentSameIntentHasOneSnapshotAndReplaySurvivesRedisLoss() throws Exception {
        try (var browser = new Browser(10);
                var executor = Executors.newFixedThreadPool(6)) {
            String operation = UUID.randomUUID().toString();
            List<Future<String>> futures = new ArrayList<>();
            for (int n = 0; n < 6; n++) {
                futures.add(executor.submit(() -> submit(browser, operation)));
            }
            Set<String> ids = new HashSet<>();
            for (var future : futures) {
                ids.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(1);
            String id = ids.iterator().next();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation", Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_audit_export_row", Integer.class))
                    .isEqualTo(1);
            run(id);
            var download = browser.get("/files/" + id);
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body())
                    .contains("9007199254741111", "'=FORMULA")
                    .doesNotContain("password_hash");
            var changed = input(operation);
            changed.put("action", "different");
            assertThat(browser.post("/system/audit-exports", changed).statusCode()).isEqualTo(409);
            try (var connection = redis.getConnectionFactory().getConnection()) {
                connection.serverCommands().flushDb();
            }
            try (var again = new Browser(10)) {
                assertThat(submit(again, operation)).isEqualTo(id);
                assertThat(
                                body(again.get("/system/audit-exports/operations/" + operation))
                                        .path("state")
                                        .asString())
                        .isEqualTo("SUCCESS");
            }
        }
    }

    @Test
    void canonicalTimeAndEmptyFiltersReplayAndOtherPrincipalsCannotRead() throws Exception {
        try (var owner = new Browser(10);
                var other = new Browser(11)) {
            String operation = UUID.randomUUID().toString(), id = submit(owner, operation);
            var equivalent = input(operation);
            equivalent.put("from", "2026-01-01T08:00:00+08:00");
            equivalent.put("module", "");
            assertThat(
                            body(owner.post("/system/audit-exports", equivalent))
                                    .path("taskId")
                                    .asString())
                    .isEqualTo(id);
            assertThat(other.get("/tasks/" + id).statusCode()).isEqualTo(404);
            assertThat(other.get("/files/" + id).statusCode()).isEqualTo(404);
            assertThat(other.get("/system/audit-exports/operations/" + operation).statusCode())
                    .isEqualTo(404);
            assertThat(submit(other, operation)).isNotEqualTo(id);
        }
    }

    @Test
    void snapshotAuditFailureRollsBackAllRegistrationAndResourceLimitsNeverTruncate()
            throws Exception {
        try (var browser = new Browser(10)) {
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_task_submit_failure"
                            + " CHECK(action<>'system:audit:export:submit')");
            try {
                assertThat(
                                browser.post(
                                                "/system/audit-exports",
                                                input(UUID.randomUUID().toString()))
                                        .statusCode())
                        .isEqualTo(500);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_task_submit_failure");
            }
            for (String table :
                    List.of("sys_operation", "sys_task", "sys_file", "sys_audit_export_row")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                        .isZero();
            }
            for (int n = 0; n < 21; n++) {
                jdbc.update(
                        "INSERT INTO"
                                + " sys_oper_log(user_id,actor_dept_id,module_name,action,success,changes,create_time)"
                                + " VALUES(10,1,'范围','fixture:export',TRUE,'{}','2026-01-02"
                                + " 00:00:00')");
            }
            var response =
                    browser.post("/system/audit-exports", input(UUID.randomUUID().toString()));
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation", Integer.class))
                    .isZero();
        }
    }

    @Test
    void expiredLeaseCannotPassOldEpochAndLockedTaskCannotBeTakenOver() throws Exception {
        try (var browser = new Browser(10);
                var executor = Executors.newSingleThreadExecutor()) {
            String id = submit(browser, UUID.randomUUID().toString());
            var old = worker.claim(UUID.randomUUID().toString());
            jdbc.update(
                    "UPDATE sys_task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))"
                            + " WHERE id=?",
                    id);
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var holding =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .execute(
                                                    status -> {
                                                        tasks.lock(id);
                                                        locked.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    10, TimeUnit.SECONDS)) {
                                                                throw new AssertionError(
                                                                        "release deadline");
                                                            }
                                                        } catch (InterruptedException error) {
                                                            throw new RuntimeException(error);
                                                        }
                                                        return true;
                                                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(worker.claim(UUID.randomUUID().toString())).isNull();
            } finally {
                release.countDown();
            }
            assertThat(holding.get(10, TimeUnit.SECONDS)).isTrue();
            var replacement = worker.claim(UUID.randomUUID().toString());
            assertThat(replacement.executionEpoch()).isGreaterThan(old.executionEpoch());
            assertThatThrownBy(() -> worker.execute(old)).isInstanceOf(BusinessException.class);
            worker.execute(replacement);
            worker.failed(old, new java.io.IOException("late failure"));
            assertThat(tasks.find(id).state()).isEqualTo(TaskState.SUCCESS);
            worker.failed(replacement, new java.io.IOException("response lost after commit"));
            assertThat(tasks.find(id).state()).isEqualTo(TaskState.SUCCESS);
        }
    }

    @Test
    void publishedFileAndProgressSurviveRollbackThenSameTaskRecovers() throws Exception {
        try (var browser = new Browser(10)) {
            String id = submit(browser, UUID.randomUUID().toString());
            var lease = worker.claim(UUID.randomUUID().toString());
            jdbc.execute(
                    "ALTER TABLE sys_oper_log ADD CONSTRAINT ck_task_finish_failure"
                            + " CHECK(action<>'system:audit:export')");
            try {
                assertThatThrownBy(() -> worker.execute(lease))
                        .isInstanceOf(RuntimeException.class);
            } finally {
                jdbc.execute("ALTER TABLE sys_oper_log DROP CHECK ck_task_finish_failure");
            }
            assertThat(tasks.find(id).state()).isEqualTo(TaskState.RUNNING);
            assertThat(tasks.operationForTask(id).state()).isEqualTo(OperationState.PENDING);
            assertThat(tasks.progress(id).phase()).isEqualTo("PUBLISHED");
            assertThat(Files.exists(object(id))).isTrue();
            assertThat(browser.get("/files/" + id).statusCode()).isEqualTo(409);
            jdbc.update(
                    "UPDATE sys_task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))"
                            + " WHERE id=?",
                    id);
            run(id);
            assertThat(body(browser.get("/tasks/" + id)).path("operationState").asString())
                    .isEqualTo("SUCCEEDED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE"
                                            + " action='system:audit:export' AND success=TRUE",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void missingAndCorruptFilesRecoverFromSameSnapshotAfterLiveLogsChange() throws Exception {
        try (var browser = new Browser(10)) {
            String id = submit(browser, UUID.randomUUID().toString());
            run(id);
            String original = browser.get("/files/" + id).body();
            jdbc.update("DELETE FROM sys_oper_log WHERE action='fixture:export'");
            Files.delete(object(id));
            assertThat(browser.get("/files/" + id).statusCode()).isEqualTo(409);
            assertThat(body(browser.get("/tasks/" + id)).path("fileState").asString())
                    .isEqualTo("RECOVERING");
            assertThat(browser.post("/tasks/" + id + "/recover", Map.of()).statusCode())
                    .isEqualTo(200);
            run(id);
            assertThat(browser.get("/files/" + id).body()).isEqualTo(original);
            Files.writeString(object(id), "corrupt");
            assertThat(browser.get("/files/" + id).statusCode()).isEqualTo(409);
            browser.post("/tasks/" + id + "/recover", Map.of());
            run(id);
            assertThat(browser.get("/files/" + id).body()).isEqualTo(original);
            try (var quarantine = Files.list(Path.of(settings.fileRoot()).resolve("quarantine"))) {
                assertThat(
                                quarantine.anyMatch(
                                        path -> path.getFileName().toString().startsWith(id)))
                        .isTrue();
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE"
                                            + " action='system:audit:export' AND success=TRUE",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void queuedCancellationAndTransientRetriesHaveFiniteBudget() throws Exception {
        try (var browser = new Browser(10)) {
            String cancelled = submit(browser, UUID.randomUUID().toString());
            assertThat(browser.post("/tasks/" + cancelled + "/cancel", Map.of()).statusCode())
                    .isEqualTo(200);
            assertThat(worker.claim(UUID.randomUUID().toString())).isNull();
            String retry = submit(browser, UUID.randomUUID().toString());
            for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
                var lease = worker.claim(UUID.randomUUID().toString());
                assertThat(lease.attempt()).isEqualTo(attempt);
                assertThat(browser.post("/tasks/" + retry + "/cancel", Map.of()).statusCode())
                        .isEqualTo(409);
                worker.failed(lease, new java.io.IOException("injected storage outage"));
                jdbc.update(
                        "UPDATE sys_task SET available_at=CURRENT_TIMESTAMP(6) WHERE id=?", retry);
            }
            assertThat(tasks.find(retry).state()).isEqualTo(TaskState.FAILED);
            assertThat(tasks.operationForTask(retry).state()).isEqualTo(OperationState.FAILED);
            assertThat(worker.claim(UUID.randomUUID().toString())).isNull();
            String expired = submit(browser, UUID.randomUUID().toString());
            jdbc.update(
                    "UPDATE sys_task SET deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))"
                            + " WHERE id=?",
                    expired);
            assertThat(worker.claim(UUID.randomUUID().toString())).isNull();
            assertThat(tasks.find(expired).errorCode()).isEqualTo(1003000010);
        }
    }

    @Test
    void revokedScopeBlocksReplayWorkerAndEveryNewDownloadRead() throws Exception {
        try (var browser = new Browser(11)) {
            String operation = UUID.randomUUID().toString(), id = submit(browser, operation);
            run(id);
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new ActorAuthenticationToken(
                                    authorization.loadCurrent(11).orElseThrow(), "test"));
            try (var file = downloads.open(id)) {
                assertThat(file.input().read(new byte[32])).isPositive();
                jdbc.update("UPDATE sys_role SET data_scope=5 WHERE id=1000");
                assertThatThrownBy(() -> file.input().read(new byte[32]))
                        .isInstanceOf(BusinessException.class);
            }
            assertThat(browser.get("/tasks/" + id).statusCode()).isEqualTo(403);
            assertThat(browser.get("/files/" + id).statusCode()).isEqualTo(403);
            assertThat(browser.post("/system/audit-exports", input(operation)).statusCode())
                    .isEqualTo(403);
            jdbc.update("UPDATE sys_role SET data_scope=1 WHERE id=1000");
            String queued = submit(browser, UUID.randomUUID().toString());
            var lease = worker.claim(UUID.randomUUID().toString());
            jdbc.update("UPDATE sys_role SET data_scope=5 WHERE id=1000");
            try {
                worker.execute(lease);
                fail("Expected scope rejection");
            } catch (BusinessException denied) {
                worker.failed(lease, denied);
            }
            assertThat(tasks.find(queued).state()).isEqualTo(TaskState.FAILED);
            assertThat(Files.exists(object(queued))).isFalse();
        }
    }

    @Test
    void permissionIntersectionProgressFenceAndAdmissionLimitAreEnforced() throws Exception {
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope)"
                        + " VALUES(1001,'LIST_ALL','全局读取',1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1001,150)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(11,1001)");
        jdbc.update("UPDATE sys_role SET data_scope=5 WHERE id=1000");
        try (var browser = new Browser(11)) {
            String id = submit(browser, UUID.randomUUID().toString());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_audit_export_row WHERE task_id=?",
                                    Integer.class,
                                    id))
                    .isZero();
            progress.record(id, 2, 3, "PUBLISHED");
            progress.record(id, 1, 999, "WRITING");
            assertThat(tasks.progress(id).processed()).isEqualTo(3);
            submit(browser, UUID.randomUUID().toString());
            submit(browser, UUID.randomUUID().toString());
            assertThat(
                            browser.post(
                                            "/system/audit-exports",
                                            input(UUID.randomUUID().toString()))
                                    .statusCode())
                    .isEqualTo(409);
        }
    }

    private Path object(String id) {
        return Path.of(settings.fileRoot())
                .toAbsolutePath()
                .resolve("objects")
                .resolve(id + ".csv");
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body()).path("data");
    }

    private final class Browser implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().build();
        final String token;

        Browser(long id) throws Exception {
            try (var login =
                    HttpClient.newBuilder()
                            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                            .build()) {
                var csrf =
                        login.send(
                                HttpRequest.newBuilder(uri("/system/auth/csrf")).build(),
                                HttpResponse.BodyHandlers.ofString());
                var protection = body(csrf);
                var request =
                        HttpRequest.newBuilder(uri("/system/auth/login"))
                                .header("Content-Type", "application/json")
                                .header("X-XSRF-TOKEN", protection.path("token").asString())
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "username",
                                                                "export_" + id,
                                                                "password",
                                                                PASSWORD))));
                var response = login.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                token = body(response).path("accessToken").asString();
            }
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + "/api/v1" + path);
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(
                    HttpRequest.newBuilder(uri(path))
                            .header("Authorization", "Bearer " + token)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object input) throws Exception {
            return client.send(
                    HttpRequest.newBuilder(uri(path))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(input)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        public void close() {
            client.close();
        }
    }
}
