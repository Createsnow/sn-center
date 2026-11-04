package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * browser profile 显式执行。真实 Spring/MySQL/Redis/Vite/浏览器闭环，全程使用一次性账号。 只终止本测试创建的 Node 进程；不读取开发密钥或写
 * Compose 数据卷。设施缺失直接失败。
 */
@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TemplateBrowserE2E {
    private static final String SECRET =
            HexFormat.of().formatHex(new SecureRandom().generateSeed(32));
    private static final String PASSWORD = "Browser9!" + SECRET.substring(0, 24);

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_template_browser")
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
        properties.add("ADTEK_DB_URL", MYSQL::getJdbcUrl);
        properties.add("ADTEK_DB_USER", MYSQL::getUsername);
        properties.add("ADTEK_DB_PASSWORD", MYSQL::getPassword);
        properties.add("ADTEK_MIGRATION_USER", MYSQL::getUsername);
        properties.add("ADTEK_MIGRATION_PASSWORD", MYSQL::getPassword);
        properties.add("ADTEK_REDIS_HOST", REDIS::getHost);
        properties.add("ADTEK_REDIS_PORT", () -> REDIS.getMappedPort(6379));
        properties.add("ADTEK_REDIS_PASSWORD", () -> SECRET);
        properties.add("ADTEK_JWT_SECRET", () -> SECRET);
        properties.add("adtek.tasks.enabled", () -> true);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @LocalServerPort int apiPort;

    @Test
    void templateBrowserWorkflowOnDisposableInfrastructure() throws Exception {
        jdbc.execute("ALTER TABLE sys_oper_log AUTO_INCREMENT=9007199254743001");
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES"
                        + " (1000,'BROWSER_AUDIT','本人审计员',5)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1000,150)");
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (9007199254741004,'browser_audit',?,'审计验收账号',1,FALSE)",
                passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (9007199254741004,1000)");
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES"
                        + " (1002,'BROWSER_EXPORT','本人导出员',5)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (1002,150),(1002,151)");
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (9007199254741005,'browser_export',?,'导出验收账号',1,FALSE)",
                passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (9007199254741005,1002)");
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (9007199254741001,'browser_admin',?,'浏览器验收管理员',1,FALSE)",
                passwords.encode(PASSWORD));
        jdbc.update(
                "INSERT INTO sys_user_role(user_id,role_id) VALUES"
                        + " (9007199254741001,1),(9007199254741001,2)");
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (9007199254741002,'browser_initial',?,'初始账号认证验收',1,TRUE)",
                passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (9007199254741002,1)");
        jdbc.update(
                "INSERT INTO"
                        + " sys_user(id,username,password_hash,display_name,dept_id,must_change_password)"
                        + " VALUES (9007199254741003,'browser_config',?,'配置验收管理员',1,FALSE)",
                passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES (9007199254741003,1)");

        jdbc.execute("ALTER TABLE equipment_asset AUTO_INCREMENT=9007199254740993");
        for (int i = 1; i <= 3; i++) {
            jdbc.update(
                    "INSERT INTO sys_user(id,username,password_hash,display_name,dept_id,must_change_password) VALUES(?,?,?,'设备验收账号',1,FALSE)",
                    9007199254741100L + i,
                    "browser_equipment" + i,
                    passwords.encode(PASSWORD));
            jdbc.update(
                    "INSERT INTO sys_user_role(user_id,role_id) VALUES(?,3)",
                    9007199254741100L + i);
        }
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,data_scope) VALUES(1003,'BROWSER_ASSET_READER','设备仅读员',1)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1003,210)");
        jdbc.update(
                "INSERT INTO sys_user(id,username,password_hash,display_name,dept_id,must_change_password) VALUES(9007199254741104,'browser_equipment_read',?,'设备只读账号',1,FALSE)",
                passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(9007199254741104,1003)");
        Path project =
                Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent().getParent();
        Path frontend = project.resolve("frontend");
        if (!Files.isRegularFile(frontend.resolve("package.json"))) {
            throw new IllegalStateException("Cannot locate frontend workspace");
        }
        Files.createDirectories(frontend.resolve("test-results"));
        int webPort;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            webPort = socket.getLocalPort();
        }
        var viteBuilder =
                new ProcessBuilder(
                                "node",
                                "node_modules/vite/bin/vite.js",
                                "--port",
                                Integer.toString(webPort),
                                "--strictPort")
                        .directory(frontend.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(
                                frontend.resolve("test-results/template-vite.log").toFile());
        viteBuilder.environment().put("ADTEK_API_TARGET", "http://127.0.0.1:" + apiPort);
        Process vite = viteBuilder.start();
        Process browser = null;
        try {
            URI web = URI.create("http://127.0.0.1:" + webPort);
            awaitReady(vite, web);
            var browserBuilder =
                    new ProcessBuilder(
                                    "node",
                                    "node_modules/@playwright/test/cli.js",
                                    "test",
                                    "--config=playwright.config.ts")
                            .directory(frontend.toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(
                                    frontend.resolve("test-results/template-playwright.log")
                                            .toFile());
            browserBuilder.environment().put("ADTEK_E2E_URL", web.toString());
            browserBuilder.environment().put("ADTEK_E2E_INITIAL_PASSWORD", PASSWORD);
            browserBuilder.environment().put("ADTEK_E2E_USERNAME", "browser_initial");
            browser = browserBuilder.start();
            assertThat(browser.waitFor(360, TimeUnit.SECONDS))
                    .as("Browser completion deadline")
                    .isTrue();
            assertThat(browser.exitValue())
                    .as("Playwright users report in frontend/test-results/browser.json")
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT must_change_password FROM sys_user WHERE"
                                            + " username='browser_user'",
                                    Boolean.class))
                    .isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT role_id FROM sys_user_role WHERE user_id=(SELECT id"
                                            + " FROM sys_user WHERE username='browser_user')",
                                    Long.class))
                    .isEqualTo(3L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE action LIKE"
                                            + " 'system:user:%%' AND success=TRUE",
                                    Integer.class))
                    .isEqualTo(5);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT data_scope FROM sys_role WHERE role_code='E2E_READER'",
                                    Integer.class))
                    .isEqualTo(5);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_role_dept rd JOIN sys_role r ON"
                                            + " r.id=rd.role_id WHERE r.role_code='E2E_READER'",
                                    Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT label_vi FROM sys_dict_item WHERE value='READY'",
                                    String.class))
                    .isEqualTo("Sẵn sàng");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE"
                                            + " user_id=9007199254741004 AND action='http:REJECT' AND"
                                            + " http_status=400 AND error_code=1002000003 AND"
                                            + " actor_dept_id=1",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE"
                                            + " user_id=9007199254741004 AND"
                                            + " action='auth:LANGUAGE_CHANGE' AND success=TRUE",
                                    Integer.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_operation WHERE"
                                            + " principal_id=9007199254741005 AND state='SUCCEEDED'",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_file f JOIN sys_task t ON"
                                            + " t.id=f.task_id WHERE f.state='READY' AND t.task_type=?",
                                    Integer.class,
                                    com.adtek.system.service.impl.AuditExportTaskHandler.TYPE))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE"
                                            + " user_id=9007199254741005 AND"
                                            + " action='system:audit:export' AND success=TRUE",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE action IN"
                                            + " ('system:role:update','system:dept:update','system:menu:update','system:dict:update')"
                                            + " AND success=TRUE",
                                    Integer.class))
                    .isEqualTo(7);

            assertThat(
                            jdbc.queryForObject(
                                    "SELECT version FROM equipment_asset WHERE code='BROWSER-EQ'",
                                    Long.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT name FROM equipment_asset WHERE code='BROWSER-EQ'",
                                    String.class))
                    .isEqualTo("校验设备");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_oper_log WHERE action='equipment:asset:update' AND success=TRUE",
                                    Integer.class))
                    .isEqualTo(1);
        } finally {
            if (browser != null) {
                stop(browser);
            }
            stop(vite);
        }
    }

    private void awaitReady(Process process, URI uri) throws Exception {
        int lastStatus = 0;
        try (var client =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(1))
                        .build()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && process.isAlive()) {
                try {
                    lastStatus =
                            client.send(
                                            HttpRequest.newBuilder(uri.resolve("/"))
                                                    .timeout(Duration.ofSeconds(2))
                                                    .build(),
                                            HttpResponse.BodyHandlers.discarding())
                                    .statusCode();
                    if (lastStatus == 200) {
                        return;
                    }
                } catch (java.io.IOException ignored) {
                    /* Vite 尚未监听，受总截止时间约束。 */
                }
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException(
                "Isolated Vite failed to become ready; last HTTP status=" + lastStatus);
    }

    private void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
