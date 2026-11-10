package com.adtek.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adtek.common.constant.CommonErrorCode;
import com.adtek.framework.web.TraceContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 真实设施集成测试：临时 MySQL 容器复用 deploy/ 下的初始化脚本，按部署方式先以 migrate profile 执行迁移，再启动 API。
 *
 * <p>验证 V1 基线、DML/DDL 账号分离、就绪探针只依赖 MySQL（MySQL 不可达时为 DOWN），以及安全链对未认证请求与缺少 CSRF 的写请求统一返回 Result 信封。
 */
@Testcontainers
class PlatformIT {
    private static final String DATABASE = "sn_center";
    private static final Path DEPLOY = Path.of("..", "..", "deploy");
    private static final String ROOT_PASSWORD = randomSecret();
    private static final String APP_PASSWORD = randomSecret();
    private static final String MIGRATION_PASSWORD = randomSecret();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    // 镜像与 deploy/compose.infra.yml、deploy/images.lock.json 一致。
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(
                            DockerImageName.parse(
                                            "mysql:8.4.11@sha256:0744ee5ef89ce6ccfa13de3e579fe6b9e27f93dd70da9c06d2c908b1b193fb8d")
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName(DATABASE)
                    .withUsername("root")
                    .withPassword(ROOT_PASSWORD)
                    .withCopyToContainer(
                            Transferable.of(ROOT_PASSWORD), "/run/secrets/mysql_root_password")
                    .withCopyToContainer(
                            Transferable.of(APP_PASSWORD), "/run/secrets/mysql_app_password")
                    .withCopyToContainer(
                            Transferable.of(MIGRATION_PASSWORD),
                            "/run/secrets/mysql_migration_password")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(DEPLOY.resolve("mysql/init-users.sh"), 0644),
                            "/docker-entrypoint-initdb.d/10-users.sh");

    @BeforeAll
    static void migrateLikeTheDeploymentJob() {
        // 与 tools\migrate.bat、compose migrate 服务相同：只提供 DDL 账号，不提供运行账号。
        try (ConfigurableApplicationContext ignored =
                new SpringApplicationBuilder(AdtekApplication.class)
                        .profiles("migrate")
                        .run(
                                "--ADTEK_DB_URL=" + jdbcUrl(),
                                "--ADTEK_MIGRATION_USER=adtek_migration",
                                "--ADTEK_MIGRATION_PASSWORD=" + MIGRATION_PASSWORD)) {
            // 迁移在启动期间完成，上下文随即关闭。
        }
    }

    @Test
    void baselineIsAppliedAndTheRuntimeAccountHasNoDdl() throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(jdbcUrl(), "adtek_app", APP_PASSWORD);
                Statement statement = connection.createStatement()) {
            try (ResultSet history =
                    statement.executeQuery(
                            "SELECT success FROM flyway_schema_history WHERE version = '1'")) {
                assertThat(history.next()).isTrue();
                assertThat(history.getBoolean(1)).isTrue();
            }
            try (ResultSet schema =
                    statement.executeQuery(
                            "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME"
                                    + " FROM information_schema.SCHEMATA"
                                    + " WHERE SCHEMA_NAME = DATABASE()")) {
                assertThat(schema.next()).isTrue();
                assertThat(schema.getString(1)).isEqualTo("utf8mb4");
                assertThat(schema.getString(2)).isEqualTo("utf8mb4_0900_as_cs");
            }
            assertThatThrownBy(() -> statement.execute("CREATE TABLE ddl_probe (id BIGINT)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("denied");
        }
    }

    @Test
    void apiIsReadyWithItsDependenciesAndRejectsUnauthenticatedRequests() throws Exception {
        try (ConfigurableApplicationContext api =
                new SpringApplicationBuilder(AdtekApplication.class)
                        .run(
                                "--ADTEK_DB_URL=" + jdbcUrl(),
                                "--ADTEK_DB_USER=adtek_app",
                                "--ADTEK_DB_PASSWORD=" + APP_PASSWORD,
                                "--ADTEK_SERVER_PORT=0")) {
            String base =
                    "http://127.0.0.1:" + api.getEnvironment().getProperty("local.server.port");
            HttpClient client = HttpClient.newHttpClient();

            assertThat(send(client, get(base + "/actuator/health/liveness")).statusCode())
                    .isEqualTo(200);
            assertThat(send(client, get(base + "/actuator/health/readiness")).statusCode())
                    .isEqualTo(200);

            HttpResponse<String> anonymous = send(client, get(base + "/api/v1/sample/items"));
            assertFailure(anonymous, 401, CommonErrorCode.UNAUTHENTICATED.code());

            HttpResponse<String> withoutCsrf =
                    send(
                            client,
                            HttpRequest.newBuilder(URI.create(base + "/api/v1/sample/items"))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build());
            assertFailure(withoutCsrf, 403, CommonErrorCode.FORBIDDEN.code());

            // 非 local profile 不开放 OpenAPI；/api 之外的路径默认拒绝。
            for (String path : List.of("/v3/api-docs", "/actuator/env", "/anything")) {
                assertThat(send(client, get(base + path)).statusCode()).as(path).isEqualTo(401);
            }
        }
    }

    @Test
    void readinessIsDownWhileMySqlIsUnreachable() throws Exception {
        // 端口 1 无服务：API 照常启动并存活，就绪探针在数据库可用前保持 DOWN（K8s 不转发流量）。
        try (ConfigurableApplicationContext api =
                new SpringApplicationBuilder(AdtekApplication.class)
                        .run(
                                "--ADTEK_DB_URL=jdbc:mysql://127.0.0.1:1/" + DATABASE,
                                "--ADTEK_DB_USER=adtek_app",
                                "--ADTEK_DB_PASSWORD=" + APP_PASSWORD,
                                "--spring.datasource.hikari.connection-timeout=1000",
                                "--ADTEK_SERVER_PORT=0")) {
            String base =
                    "http://127.0.0.1:" + api.getEnvironment().getProperty("local.server.port");
            HttpClient client = HttpClient.newHttpClient();

            assertThat(send(client, get(base + "/actuator/health/liveness")).statusCode())
                    .isEqualTo(200);
            assertThat(send(client, get(base + "/actuator/health/readiness")).statusCode())
                    .isEqualTo(503);
        }
    }

    private static void assertFailure(HttpResponse<String> response, int status, int code) {
        assertThat(response.statusCode()).isEqualTo(status);
        String traceId = response.headers().firstValue(TraceContext.HEADER).orElseThrow();
        assertThat(traceId).matches("[0-9a-f]{32}");
        Map<?, ?> body = JSON.readValue(response.body(), Map.class);
        assertThat(body.get("code")).isEqualTo(code);
        assertThat(body.get("traceId")).isEqualTo(traceId);
        assertThat(body.get("data")).isNull();
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request)
            throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String jdbcUrl() {
        return "jdbc:mysql://"
                + MYSQL.getHost()
                + ":"
                + MYSQL.getMappedPort(3306)
                + "/"
                + DATABASE
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                + "&allowPublicKeyRetrieval=true&sslMode=DISABLED";
    }

    /** 与 deploy/init-secrets.sh 相同格式（64 位十六进制），满足初始化脚本的字符校验。 */
    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
