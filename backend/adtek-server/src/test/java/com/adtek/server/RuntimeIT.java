package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.RateLimitService;
import com.adtek.framework.security.SessionStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * 真实 MySQL/Redis 的空库与启动装配检查。容器使用随机端口、独立数据和一次性密码。 缺 Docker 必须失败，不设置 disabledWithoutDocker；不触碰
 * Compose 开发卷或旧库。
 */
@Testcontainers
@org.springframework.test.context.ActiveProfiles("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 每类使用独立容器，结束时关闭连接池/Redis线程，不缓存已经失效的容器连接。
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RuntimeIT {
    private static final String PASSWORD =
            HexFormat.of().formatHex(new SecureRandom().generateSeed(32));

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_sn_it")
                    .withUsername("adtek_it")
                    .withPassword(PASSWORD)
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_as_cs",
                            "--default-time-zone=+00:00");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.4.7-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", PASSWORD);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("ADTEK_DB_URL", MYSQL::getJdbcUrl);
        properties.add("ADTEK_DB_USER", MYSQL::getUsername);
        properties.add("ADTEK_DB_PASSWORD", MYSQL::getPassword);
        properties.add("ADTEK_MIGRATION_USER", MYSQL::getUsername);
        properties.add("ADTEK_MIGRATION_PASSWORD", MYSQL::getPassword);
        properties.add("ADTEK_REDIS_HOST", REDIS::getHost);
        properties.add("ADTEK_REDIS_PORT", () -> REDIS.getMappedPort(6379));
        properties.add("ADTEK_REDIS_PASSWORD", () -> PASSWORD);
        properties.add("ADTEK_JWT_SECRET", () -> PASSWORD);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired SessionStore sessions;
    @Autowired RateLimitService rateLimits;
    @LocalServerPort int port;

    @Test
    void openApiDescribesIntegerErrorCodesStringIdsAndFilterErrors() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var api = new tools.jackson.databind.ObjectMapper().readTree(response.body());
            var schemas = api.path("components").path("schemas");
            assertThat(
                            schemas.path("ResultAccessTokenVO")
                                    .path("properties")
                                    .path("code")
                                    .path("format")
                                    .asString())
                    .isEqualTo("int32");
            assertThat(
                            schemas.path("UserProfileVO")
                                    .path("properties")
                                    .path("id")
                                    .path("type")
                                    .asString())
                    .isEqualTo("string");
            assertThat(
                            api.path("paths")
                                    .path("/api/v1/system/auth/csrf")
                                    .path("get")
                                    .has("parameters"))
                    .isFalse();
            assertThat(
                            api.path("paths")
                                    .path("/api/v1/system/auth/login")
                                    .path("post")
                                    .path("responses")
                                    .has("429"))
                    .isTrue();
            var creator = schemas.path("RuleVersionVO").path("properties").path("createdBy");
            var creatorTypes = new java.util.ArrayList<String>();
            creator.path("type").forEach(value -> creatorTypes.add(value.asString()));
            assertThat(creatorTypes).containsExactlyInAnyOrder("string", "null");
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target/current-openapi.json"), response.body());
        }
    }

    @Test
    void initializesFlywayAndKeepsRealRedisReachable() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",
                                Integer.class))
                .isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
        redis.opsForValue().set("adtek:it:runtime", "verified");
        assertThat(redis.opsForValue().get("adtek:it:runtime")).isEqualTo("verified");
        redis.delete("adtek:it:runtime");
    }

    @Test
    void readinessIsPublicButApplicationDefaultsToClosed() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var ready =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/actuator/health/readiness"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(ready.statusCode()).isEqualTo(200);
            assertThat(ready.body()).contains("\"status\":\"UP\"");
            var denied =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/api/system/users"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(denied.body())
                    .contains("\"code\":1003000001", "\"traceId\"")
                    .doesNotContain("password", "stackTrace");
        }
    }

    @Test
    void sessionRotationRejectsReplayAndVersionMismatch() {
        var session = sessions.create(101, 7, Duration.ofMinutes(5));
        sessions.requireActive(session.sessionId(), 101, 7);
        assertThatThrownBy(() -> sessions.requireActive(session.sessionId(), 101, 6))
                .isInstanceOf(BusinessException.class);
        assertThat(redis.getExpire("adtek:session:" + session.sessionId())).isBetween(1L, 300L);
        var rotated = sessions.rotate(session.credential(), Duration.ofMinutes(5));
        assertThat(rotated.credential()).isNotEqualTo(session.credential());
        sessions.requireActive(session.sessionId(), 101, 7);
        assertThatThrownBy(() -> sessions.rotate(session.credential(), Duration.ofMinutes(5)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> sessions.requireActive(session.sessionId(), 101, 7))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void concurrentRefreshConsumesCredentialAtMostOnce() throws Exception {
        var session = sessions.create(102, 1, Duration.ofMinutes(5));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> attempt =
                    () -> {
                        ready.countDown();
                        if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new AssertionError("Start barrier timeout");
                        }
                        try {
                            sessions.rotate(session.credential(), Duration.ofMinutes(5));
                            return true;
                        } catch (BusinessException error) {
                            assertThat(error.getError().code()).isEqualTo(1_003_000_001);
                            return false;
                        }
                    };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get() ^ second.get()).isTrue();
        }
        // 重用旧凭据使整个刷新家族失效，避免同时存活的新旧授权。
        assertThatThrownBy(() -> sessions.requireActive(session.sessionId(), 102, 1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rateLimitCountsConcurrentRequestsAndSeparatesSubjects() throws Exception {
        String subject = java.util.UUID.randomUUID().toString();
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 8; index++) {
                attempts.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                        throw new AssertionError("Start barrier timeout");
                                    }
                                    try {
                                        rateLimits.require(
                                                "test:concurrent",
                                                subject,
                                                3,
                                                Duration.ofMinutes(1));
                                        return true;
                                    } catch (BusinessException error) {
                                        assertThat(error.getError().httpStatus()).isEqualTo(429);
                                        assertThat(
                                                        Long.parseLong(
                                                                error.getParams()
                                                                        .get("retryAfter")))
                                                .isBetween(1L, 60L);
                                        return false;
                                    }
                                }));
            }
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) {
                if (attempt.get()) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(3);
        }
        rateLimits.require("test:concurrent", subject + "-different", 3, Duration.ofMinutes(1));
        rateLimits.require("test:other-action", subject, 3, Duration.ofMinutes(1));
    }
}
