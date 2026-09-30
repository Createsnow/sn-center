package com.adtek.server;

import static org.assertj.core.api.Assertions.*;

import com.adtek.integration.legacy.LegacySnapshotFactory;
import com.adtek.sn.config.LegacyStageFactory;
import com.adtek.sn.dto.*;
import com.adtek.sn.service.LegacyStageService;
import com.adtek.sn.service.support.LegacySnapshotPackage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 两个独立MySQL实例：旧V1快照→新Flyway staging；明确不宣称业务推广或生产切换已完成。 */
@Testcontainers
class LegacyStageIT {
    static final String PASSWORD = UUID.randomUUID().toString();

    @Container
    static final MySQLContainer SOURCE =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("legacy_stage_source")
                    .withUsername("source_it")
                    .withPassword(PASSWORD);

    @Container
    static final MySQLContainer TARGET =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("legacy_stage_target")
                    .withUsername("target_it")
                    .withPassword(PASSWORD);

    static DriverManagerDataSource source, target;
    static JdbcTemplate jdbc;
    @TempDir Path root;
    LegacyStageService service;
    String snapshotId;

    @BeforeAll
    static void databases() throws Exception {
        source = new DriverManagerDataSource(SOURCE.getJdbcUrl(), SOURCE.getUsername(), PASSWORD);
        target = new DriverManagerDataSource(TARGET.getJdbcUrl(), TARGET.getUsername(), PASSWORD);
        try (var c = source.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy/repository-v1.sql"));
        }
        var old = new JdbcTemplate(source);
        for (int i = 1; i <= 5; i++)
            old.update(
                    "INSERT INTO sn_factory(factory_code,factory_name,source,created_at,updated_at)"
                            + " VALUES(?,?,'MANUAL','2026-01-01','2026-01-01')",
                    "F" + i,
                    "合成工厂" + i);
        Flyway.configure().dataSource(target).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(target);
    }

    @BeforeEach
    void snapshot() {
        for (String table :
                List.of(
                        "sn_legacy_id_map",
                        "sn_legacy_row",
                        "sn_legacy_migration_table",
                        "sn_legacy_migration_run")) jdbc.update("DELETE FROM " + table);
        snapshotId = UUID.randomUUID().toString();
        LegacySnapshotFactory.create(source)
                .capture(
                        new LegacySnapshotCommandDTO(
                                snapshotId,
                                SOURCE.getDatabaseName(),
                                "sn-python-repository-v1",
                                "Asia/Shanghai",
                                1000,
                                1048576,
                                root.resolve(snapshotId)));
        service = LegacyStageFactory.create(target, TARGET.getDatabaseName(), root);
    }

    private LegacyStageCommandDTO cmd(boolean apply) {
        return new LegacyStageCommandDTO(
                UUID.randomUUID().toString(), snapshotId, "legacy-v1-staging-v1", apply, 2);
    }

    @Test
    void dryRunAndStageNeverActivateBusinessRows() {
        assertThat(service.stage(cmd(false)).state()).isEqualTo("DRY_RUN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sn_legacy_migration_run", Integer.class))
                .isZero();
        var command = cmd(true);
        var result = service.stage(command);
        assertThat(result.state()).isEqualTo("STAGED");
        assertThat(result.readyForPromotion()).isFalse();
        assertThat(result.rows()).isEqualTo("5");
        assertThat(service.stage(command)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_legacy_row", Integer.class))
                .isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sn_legacy_migration_table WHERE"
                                        + " state='STAGED'",
                                Integer.class))
                .isEqualTo(19);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_factory", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
    }

    @Test
    void committedChunkSurvivesFailureAndResumeChecksExistingRows() {
        var command = cmd(true);
        jdbc.execute("ALTER TABLE sn_legacy_row ADD CONSTRAINT it_fail_four CHECK(ordinal<>4)");
        try {
            assertThatThrownBy(() -> service.stage(command)).hasMessageStartingWith("LEGACY_SQL_");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_legacy_row", Integer.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_ordinal FROM sn_legacy_migration_table WHERE"
                                            + " table_name='sn_factory'",
                                    Integer.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM sn_legacy_migration_run", String.class))
                    .isEqualTo("FAILED");
        } finally {
            jdbc.execute("ALTER TABLE sn_legacy_row DROP CHECK it_fail_four");
        }
        var resume =
                new LegacyStageCommandDTO(
                        command.runId(), snapshotId, command.mappingVersion(), true, 3);
        assertThat(service.stage(resume).rows()).isEqualTo("5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_legacy_row", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void changedSourceCannotContinueAnExistingRun() throws Exception {
        var command = cmd(true);
        service.stage(command);
        String other = UUID.randomUUID().toString();
        LegacySnapshotFactory.create(source)
                .capture(
                        new LegacySnapshotCommandDTO(
                                other,
                                SOURCE.getDatabaseName(),
                                "sn-python-repository-v1",
                                "Asia/Shanghai",
                                1000,
                                1048576,
                                root.resolve(other)));
        assertThatThrownBy(
                        () ->
                                service.stage(
                                        new LegacyStageCommandDTO(
                                                command.runId(),
                                                other,
                                                command.mappingVersion(),
                                                true,
                                                2)))
                .hasMessage("LEGACY_RUN_CONFLICT");
        Path file = root.resolve(snapshotId + "/sn_factory.ndjson");
        Files.writeString(file, Files.readString(file).replace("合成工厂1", "变更工厂1"));
        assertThatThrownBy(() -> service.stage(command)).hasMessage("LEGACY_SNAPSHOT_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_legacy_row", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void changedStagedPayloadCannotHideBehindStoredDigest() {
        var command = cmd(true);
        service.stage(command);
        jdbc.update(
                "UPDATE sn_legacy_row SET payload=JSON_SET(payload,'$.factory_name','tampered')"
                        + " WHERE table_name='sn_factory' AND ordinal=1");
        assertThatThrownBy(() -> service.stage(command)).hasMessage("LEGACY_STAGE_ROW_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT state FROM sn_legacy_migration_run", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void sourceInstanceWrongTargetAndConcurrentWriterAreRefused() throws Exception {
        assertThatThrownBy(
                        () ->
                                LegacyStageFactory.create(source, SOURCE.getDatabaseName(), root)
                                        .stage(cmd(true)))
                .hasMessage("LEGACY_TARGET_IS_SOURCE_INSTANCE");
        assertThatThrownBy(
                        () ->
                                LegacyStageFactory.create(target, "wrong_schema", root)
                                        .stage(cmd(true)))
                .hasMessage("LEGACY_TARGET_MISMATCH");
        String lock =
                "adtek:legacy:"
                        + LegacySnapshotPackage.sha(
                                        TARGET.getDatabaseName().getBytes(StandardCharsets.UTF_8))
                                .substring(0, 40);
        try (var c = target.getConnection();
                var p = c.prepareStatement("SELECT GET_LOCK(?,0)")) {
            p.setString(1, lock);
            p.execute();
            assertThatThrownBy(() -> service.stage(cmd(true))).hasMessage("LEGACY_BUSY");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sn_legacy_migration_run", Integer.class))
                .isZero();
    }
}
