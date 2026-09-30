package com.adtek.server;

import static com.adtek.sn.dto.MaintenanceCommandDTO.Action.*;

import static org.assertj.core.api.Assertions.*;

import com.adtek.sn.config.SnMaintenanceFactory;
import com.adtek.sn.dto.MaintenanceCommandDTO;
import com.adtek.sn.mapper.MaintenanceMapper;
import com.adtek.sn.service.SnMaintenanceService;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 独立MySQL验证真实分区DDL、连接锁、恢复与内容校验；无业务库/开发卷/旧.env访问。 */
@Testcontainers
class SnMaintenanceIT {
    @TempDir Path proofRoot;

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("adtek_maintenance_it")
                    .withUsername("maintenance_it")
                    .withPassword(UUID.randomUUID().toString())
                    .withCommand("--default-time-zone=+00:00");

    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static SnMaintenanceService service;

    @BeforeAll
    static void schema() {
        source =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        service = SnMaintenanceFactory.create(source, MYSQL.getDatabaseName());
    }

    @BeforeEach
    void reset() {
        for (String table :
                List.of(
                        "sn_maintenance_run",
                        "sn_preview",
                        "sn_audit",
                        "sn_audit_archive",
                        "sn_item")) jdbc.update("DELETE FROM " + table);
    }

    private MaintenanceCommandDTO command(
            MaintenanceCommandDTO.Action action, boolean apply, YearMonth month) {
        return new MaintenanceCommandDTO(
                action, UUID.randomUUID().toString(), apply, month, 3, 100000);
    }

    private YearMonth oldMonth() {
        return YearMonth.now(ZoneOffset.UTC).minusMonths(2);
    }

    private void audit(String id, YearMonth month, String name) {
        jdbc.update(
                "INSERT INTO"
                        + " sn_audit(id,created_at,actor_id,operator,operator_name,roles,source,action,result,quantity,detail)"
                        + " VALUES(?,?,1,'operator',?,'[\"SN_HQ\"]','PAGE','sn:acquire:take','SUCCESS',9007199254740993,JSON_OBJECT('note','=x'))",
                id,
                month.atDay(2).atStartOfDay(),
                name);
    }

    private void preview(String id, boolean expired, boolean bound) {
        jdbc.update(
                "INSERT INTO sn_preview(id,actor_id,pi,bill_id,payload,expires_at,task_id)"
                        + " VALUES(?,1,'PI',1,'{}',?,?)",
                id,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(expired ? -2 : 2),
                bound ? UUID.randomUUID().toString() : null);
    }

    private RestoreProofFixture proof() throws Exception {
        try (var c = source.getConnection()) {
            return new RestoreProofFixture(
                    proofRoot,
                    MYSQL.getDatabaseName(),
                    jdbc.queryForObject("SELECT @@server_uuid", String.class),
                    oldMonth(),
                    new MaintenanceMapper().auditDigest(c, "sn_audit", oldMonth()));
        }
    }

    private MaintenanceCommandDTO finalizeCommand(RestoreProofFixture proof, boolean apply) {
        return new MaintenanceCommandDTO(
                ARCHIVE_FINALIZE,
                UUID.randomUUID().toString(),
                apply,
                oldMonth(),
                3,
                100000,
                proof.directory);
    }

    @Test
    void dmlAndTerminalRecordAreAtomic() {
        audit(UUID.randomUUID().toString(), oldMonth(), "original");
        preview(UUID.randomUUID().toString(), true, false);
        jdbc.execute(
                "ALTER TABLE sn_maintenance_run ADD CONSTRAINT it_reject_success CHECK"
                        + " (state<>'SUCCEEDED')");
        try {
            assertThatThrownBy(() -> service.execute(command(CLEAN_PREVIEWS, true, oldMonth())))
                    .hasMessageStartingWith("MAINTENANCE_SQL_");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_preview", Integer.class))
                    .isEqualTo(1);
            assertThatThrownBy(() -> service.execute(command(ARCHIVE_COPY, true, oldMonth())))
                    .hasMessageStartingWith("MAINTENANCE_SQL_");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit_archive", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForList("SELECT state FROM sn_maintenance_run", String.class))
                    .containsOnly("FAILED");
        } finally {
            jdbc.execute("ALTER TABLE sn_maintenance_run DROP CHECK it_reject_success");
        }
    }

    @Test
    void finalizeRemovesOnlyProvenMonthAndReplaysResult() throws Exception {
        audit(UUID.randomUUID().toString(), oldMonth(), "past");
        audit(UUID.randomUUID().toString(), oldMonth().plusMonths(1), "other");
        service.execute(command(ARCHIVE_COPY, true, oldMonth()));
        var proof = proof();
        var guarded = SnMaintenanceFactory.create(source, MYSQL.getDatabaseName(), proofRoot);
        assertThat(guarded.execute(finalizeCommand(proof, false)).state()).isEqualTo("DRY_RUN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit", Integer.class))
                .isEqualTo(2);
        var cmd = finalizeCommand(proof, true);
        var result = guarded.execute(cmd);
        assertThat(result.state()).isEqualTo("ARCHIVED_ONLINE_REMOVED");
        assertThat(guarded.execute(cmd)).isEqualTo(result);
        assertThat(jdbc.queryForList("SELECT operator_name FROM sn_audit", String.class))
                .containsExactly("other");
        assertThat(jdbc.queryForList("SELECT operator_name FROM sn_audit_archive", String.class))
                .containsExactly("past");
    }

    @Test
    void finalizeRejectsChangedRowsAndWrongServerWithoutDeletion() throws Exception {
        audit(UUID.randomUUID().toString(), oldMonth(), "past");
        service.execute(command(ARCHIVE_COPY, true, oldMonth()));
        var proof = proof();
        var guarded = SnMaintenanceFactory.create(source, MYSQL.getDatabaseName(), proofRoot);
        jdbc.update("UPDATE sn_audit SET operator_name='drift'");
        assertThatThrownBy(() -> guarded.execute(finalizeCommand(proof, true)))
                .hasMessage("ARCHIVE_RESTORE_SNAPSHOT_CHANGED");
        jdbc.update("UPDATE sn_audit SET operator_name='past'");
        proof.manifest.put(
                "sourceIdentity", UUID.randomUUID() + "\t8.4.11\t" + MYSQL.getDatabaseName());
        proof.save();
        assertThatThrownBy(() -> guarded.execute(finalizeCommand(proof, true)))
                .hasMessage("ARCHIVE_RESTORE_SOURCE_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void finalizeRollsBackRemovalIfTerminalRecordFails() throws Exception {
        audit(UUID.randomUUID().toString(), oldMonth(), "past");
        service.execute(command(ARCHIVE_COPY, true, oldMonth()));
        var proof = proof();
        var guarded = SnMaintenanceFactory.create(source, MYSQL.getDatabaseName(), proofRoot);
        var cmd = finalizeCommand(proof, true);
        jdbc.execute(
                "ALTER TABLE sn_maintenance_run ADD CONSTRAINT it_finalize_success CHECK"
                        + " (action<>'ARCHIVE_FINALIZE' OR state<>'SUCCEEDED')");
        try {
            assertThatThrownBy(() -> guarded.execute(cmd))
                    .hasMessageStartingWith("MAINTENANCE_SQL_");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit_archive", Integer.class))
                    .isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE sn_maintenance_run DROP CHECK it_finalize_success");
        }
        assertThat(guarded.execute(cmd).state()).isEqualTo("ARCHIVED_ONLINE_REMOVED");
    }

    @Test
    void dryRunDoesNotWriteSchemaOrRunRows() {
        var before =
                jdbc.queryForList(
                        "SELECT table_name,partition_name,partition_description FROM"
                                + " information_schema.partitions WHERE table_schema=DATABASE() ORDER"
                                + " BY table_name,partition_ordinal_position");
        var result = service.execute(command(PARTITIONS, false, YearMonth.of(2040, 1)));
        assertThat(result.applied()).isFalse();
        assertThat(result.statements()).isNotEmpty();
        assertThat(
                        jdbc.queryForList(
                                "SELECT table_name,partition_name,partition_description FROM"
                                        + " information_schema.partitions WHERE table_schema=DATABASE()"
                                        + " ORDER BY table_name,partition_ordinal_position"))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_maintenance_run", Integer.class))
                .isZero();
    }

    @Test
    void partitionsPreservePopulatedFallbackAndRetryCompletedRun() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO"
                        + " sn_item(gen_month,id,sn,pi,customer,material,source,status,bill_no,source_pi,task_id)"
                        + " VALUES('2027-02-01',?,'S','P','','','IMPORT','PRINTED','','P',?)",
                id,
                id);
        audit(UUID.randomUUID().toString(), YearMonth.of(2027, 2), "甲");
        var cmd = command(PARTITIONS, true, YearMonth.of(2027, 1));
        var result = service.execute(cmd);
        assertThat(result.statements()).isNotEmpty();
        assertThat(service.execute(cmd)).isEqualTo(result);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sn_item PARTITION(p202702) WHERE id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sn_audit PARTITION(p202702)", Integer.class))
                .isEqualTo(1);
        assertThat(service.execute(command(PARTITIONS, false, YearMonth.of(2027, 1))).statements())
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        new MaintenanceCommandDTO(
                                                CLEAN_PREVIEWS,
                                                cmd.runId(),
                                                true,
                                                cmd.month(),
                                                3,
                                                100000)))
                .hasMessage("MAINTENANCE_RUN_CONFLICT");
    }

    @Test
    void failedDdlRunCanResumeAfterSomeBoundariesExist() {
        var cmd = command(PARTITIONS, true, YearMonth.of(2030, 1));
        var plan =
                service.execute(
                        new MaintenanceCommandDTO(
                                PARTITIONS, cmd.runId(), false, cmd.month(), 3, 100000));
        jdbc.execute(plan.statements().getFirst());
        // 模拟DDL已自动提交而进程尚未记录成功的崩溃边界。
        var result = service.execute(cmd);
        assertThat(result.state()).isEqualTo("SUCCEEDED");
        assertThat(service.execute(command(PARTITIONS, false, cmd.month())).statements()).isEmpty();
    }

    @Test
    void connectionLockRejectsAnotherMaintainerWithoutWritingRun() throws Exception {
        String hash =
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                MYSQL.getDatabaseName()
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
        String lock = "adtek:maint:" + hash.substring(0, 40);
        try (var c = source.getConnection();
                var p = c.prepareStatement("SELECT GET_LOCK(?,0)")) {
            p.setString(1, lock);
            p.execute();
            assertThatThrownBy(() -> service.execute(command(CLEAN_PREVIEWS, true, oldMonth())))
                    .hasMessage("MAINTENANCE_BUSY");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_maintenance_run", Integer.class))
                    .isZero();
        }
        assertThat(service.execute(command(CLEAN_PREVIEWS, true, oldMonth())).state())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void refusesWrongSchemaAndUnexpectedPartitionStructure() {
        assertThatThrownBy(
                        () ->
                                SnMaintenanceFactory.create(source, "different_schema")
                                        .execute(command(PARTITIONS, false, oldMonth())))
                .hasMessage("MAINTENANCE_SCHEMA_MISMATCH");
        assertThatThrownBy(() -> SnMaintenanceFactory.create(source, "x;DROP"))
                .hasMessage("MAINTENANCE_SCHEMA_ALLOWLIST");
        jdbc.execute("ALTER TABLE sn_audit_archive REMOVE PARTITIONING");
        try {
            assertThatThrownBy(() -> service.execute(command(PARTITIONS, true, oldMonth())))
                    .hasMessage("PARTITION_STRUCTURE");
        } finally {
            jdbc.execute(
                    "ALTER TABLE sn_audit_archive PARTITION BY RANGE COLUMNS(created_at) (PARTITION"
                            + " pmax VALUES LESS THAN(MAXVALUE))");
        }
        assertThat(jdbc.queryForObject("SELECT state FROM sn_maintenance_run", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void onlyExpiredUnboundPreviewsAreRemovedAndLimitIsEnforced() {
        preview(UUID.randomUUID().toString(), true, false);
        preview(UUID.randomUUID().toString(), true, false);
        preview(UUID.randomUUID().toString(), true, true);
        preview(UUID.randomUUID().toString(), false, false);
        var cmd =
                new MaintenanceCommandDTO(
                        CLEAN_PREVIEWS, UUID.randomUUID().toString(), true, oldMonth(), 3, 1);
        assertThat(service.execute(cmd).affectedRows()).isEqualTo("1");
        assertThat(service.execute(cmd).affectedRows()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_preview", Integer.class))
                .isEqualTo(3);
        assertThat(service.execute(command(CLEAN_PREVIEWS, true, oldMonth())).affectedRows())
                .isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_preview", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void cleanupWaitsForSubmissionThenPreservesBoundPreview() throws Exception {
        String id = UUID.randomUUID().toString();
        preview(id, true, false);
        try (var c = source.getConnection();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            c.setAutoCommit(false);
            try (var p =
                    c.prepareStatement(
                            "SELECT revision FROM sn_snapshot_control WHERE id=1 FOR UPDATE")) {
                p.execute();
            }
            var started = new CountDownLatch(1);
            var task =
                    pool.submit(
                            () -> {
                                started.countDown();
                                return service.execute(command(CLEAN_PREVIEWS, true, oldMonth()));
                            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            try (var p = c.prepareStatement("UPDATE sn_preview SET task_id=? WHERE id=?")) {
                p.setString(1, UUID.randomUUID().toString());
                p.setString(2, id);
                p.executeUpdate();
            }
            c.commit();
            assertThat(task.get(10, TimeUnit.SECONDS).affectedRows()).isEqualTo("0");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sn_preview WHERE id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(1);
        }
    }

    @Test
    void copiesOriginalAuditAndKeepsOnlineWithExactDigest() {
        audit(UUID.randomUUID().toString(), oldMonth(), "甲🧾=1");
        audit(UUID.randomUUID().toString(), oldMonth(), "null");
        var dry = service.execute(command(ARCHIVE_COPY, false, oldMonth()));
        assertThat(dry.sourceDigest()).isNotEqualTo(dry.archiveDigest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit_archive", Integer.class))
                .isZero();
        var result = service.execute(command(ARCHIVE_COPY, true, oldMonth()));
        assertThat(result.sourceDigest()).isEqualTo(result.archiveDigest());
        assertThat(result.affectedRows()).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit", Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT quantity FROM sn_audit_archive LIMIT 1", String.class))
                .isEqualTo("9007199254740993");
        assertThat(service.execute(command(ARCHIVE_COPY, true, oldMonth())).sourceDigest())
                .isEqualTo(result.sourceDigest());
        assertThat(service.execute(command(ARCHIVE_VERIFY, false, oldMonth())).state())
                .isEqualTo("COPIED_VERIFIED_ONLINE_RETAINED");
    }

    @Test
    void archiveConflictRollsBackOtherCopiesAndRecordsFailure() {
        String id = UUID.randomUUID().toString();
        audit(id, oldMonth(), "old");
        jdbc.update("INSERT INTO sn_audit_archive SELECT * FROM sn_audit");
        jdbc.update("UPDATE sn_audit_archive SET operator_name='changed'");
        audit(UUID.randomUUID().toString(), oldMonth(), "missing");
        assertThatThrownBy(() -> service.execute(command(ARCHIVE_COPY, true, oldMonth())))
                .hasMessage("ARCHIVE_CONTENT_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit_archive", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM sn_maintenance_run", String.class))
                .isEqualTo("FAILED");
        jdbc.update("UPDATE sn_audit_archive SET operator_name='old'");
        String run = jdbc.queryForObject("SELECT run_id FROM sn_maintenance_run", String.class);
        assertThat(
                        service.execute(
                                        new MaintenanceCommandDTO(
                                                ARCHIVE_COPY, run, true, oldMonth(), 3, 100000))
                                .affectedRows())
                .isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT attempts FROM sn_maintenance_run", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void archiveRejectsOpenMonthMissingRowsAndResourceOverflow() {
        audit(UUID.randomUUID().toString(), oldMonth(), "one");
        audit(UUID.randomUUID().toString(), oldMonth(), "two");
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        command(ARCHIVE_COPY, true, YearMonth.now(ZoneOffset.UTC))))
                .hasMessage("ARCHIVE_MONTH_NOT_CLOSED");
        assertThatThrownBy(() -> service.execute(command(ARCHIVE_VERIFY, false, oldMonth())))
                .hasMessage("ARCHIVE_CONTENT_MISMATCH");
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        new MaintenanceCommandDTO(
                                                ARCHIVE_COPY,
                                                UUID.randomUUID().toString(),
                                                true,
                                                oldMonth(),
                                                3,
                                                1)))
                .hasMessage("ARCHIVE_ROW_LIMIT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sn_audit_archive", Integer.class))
                .isZero();
    }
}
