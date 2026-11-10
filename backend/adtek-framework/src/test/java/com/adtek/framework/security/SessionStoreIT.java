package com.adtek.framework.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.adtek.framework.lock.NamedLockService;
import com.adtek.framework.mybatis.MonthlyPartitionMaintainer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;

/** framework 的 MySQL 设施（临时 MySQL 容器）：framework_session 迁移、会话签发 / 校验 / 顶替 / 吊销、命名锁互斥、月分区维护。 */
@Testcontainers
class SessionStoreIT {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(
                            DockerImageName.parse(
                                            "mysql:8.4.11@sha256:0744ee5ef89ce6ccfa13de3e579fe6b9e27f93dd70da9c06d2c908b1b193fb8d")
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("sn_center");

    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl() + "?connectionTimeZone=UTC&useAffectedRows=true",
                        MYSQL.getUsername(),
                        MYSQL.getPassword());
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/framework")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(ds);
    }

    private static SessionService sessions() {
        return new SessionService(
                jdbc,
                new SecurityProperties("x".repeat(40), "local", 3600, true, List.of(), List.of()));
    }

    @Test
    void pageSessionsReplaceEachOtherAndCanBeRevoked() {
        SessionService s = sessions();
        SessionService.Issued a = s.issue(7, 1, SessionService.Kind.PAGE);
        assertThat(s.verify(a.token()).orElseThrow().revokeReason()).isNull();
        SessionService.Issued b = s.issue(7, 1, SessionService.Kind.PAGE);
        assertThat(s.verify(a.token()).orElseThrow().revokeReason())
                .isEqualTo(SessionService.REASON_REPLACED);
        assertThat(s.verify(b.token()).orElseThrow().revokeReason()).isNull();
        s.revokeAll(7, SessionService.REASON_PASSWORD);
        assertThat(s.verify(b.token()).orElseThrow().revokeReason())
                .isEqualTo(SessionService.REASON_PASSWORD);
        assertThat(s.verify("not-a-token")).isEmpty();
    }

    @Test
    void namedLocksAreExclusiveAcrossConnections() {
        NamedLockService locks = new NamedLockService(ds, "it");
        Optional<NamedLockService.Held> first = locks.tryAcquire("pi", List.of("PI-1"), 0);
        assertThat(first).isPresent();
        assertThat(locks.tryAcquire("pi", List.of("PI-1"), 0)).isEmpty();
        assertThat(locks.isUsed("pi", "PI-1")).isTrue();
        first.get().release();
        try (NamedLockService.Held again =
                locks.tryAcquire("pi", List.of("PI-1"), 0).orElseThrow()) {
            assertThat(again).isNotNull();
        }
    }

    @Test
    void monthlyPartitionsAreAddedFromPmax() {
        jdbc.execute(
                "CREATE TABLE part_probe (id BIGINT NOT NULL, created_at DATETIME NOT NULL, PRIMARY KEY (id, created_at))"
                        + " PARTITION BY RANGE COLUMNS (created_at) (PARTITION p202601 VALUES LESS THAN"
                        + " ('2026-02-01 00:00:00'), PARTITION pmax VALUES LESS THAN (MAXVALUE))");
        MonthlyPartitionMaintainer m = new MonthlyPartitionMaintainer(jdbc);
        m.addMonths(
                "part_probe",
                List.of(new MonthlyPartitionMaintainer.MonthDef("p202602", "2026-03-01 00:00:00")));
        assertThat(m.partitions("part_probe"))
                .extracting(MonthlyPartitionMaintainer.Partition::name)
                .containsExactly("p202601", "p202602", "pmax");
        m.dropPartition("part_probe", "p202601");
        assertThat(m.partitions("part_probe")).hasSize(2);
    }
}
