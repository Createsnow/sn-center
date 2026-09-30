package com.adtek.sn.service.impl;

import com.adtek.sn.dto.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.LegacyInventoryService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.LegacyInventoryResultVO;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

/** 有限库存阶段整批原子，原始快照/staging检查点与本阶段不同，不宣称大规模业务导入已验收。 */
public final class LegacyInventoryServiceImpl implements LegacyInventoryService {
    private final DataSource target;
    private final String schema;
    private final Path root;
    private final LegacyMasterServiceImpl master;
    private final LegacyStageMapper stage;
    private final LegacyMasterMapper rows;
    private final ObjectMapper json = new ObjectMapper();

    public LegacyInventoryServiceImpl(
            DataSource target,
            String schema,
            Path root,
            LegacyMasterServiceImpl master,
            LegacyStageMapper stage,
            LegacyMasterMapper rows) {
        if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,63}"))
            throw fail("TARGET_ALLOWLIST");
        this.target = target;
        this.schema = schema;
        this.root = root;
        this.master = master;
        this.stage = stage;
        this.rows = rows;
    }

    @Override
    public LegacyInventoryResultVO promote(LegacyMasterCommandDTO cmd) {
        try {
            var snapshot = new LegacySnapshotPackage(root, cmd.snapshotId());
            var main = new LegacyMasterPlan(snapshot);
            var plan = new LegacyInventoryPlan(snapshot, main, cmd.runId());
            String fingerprint = snapshot.manifest().sourceFingerprint(),
                    masterDigest = main.digest(fingerprint),
                    digest = plan.digest(fingerprint, masterDigest);
            var result =
                    new LegacyInventoryResultVO(
                            cmd.runId(),
                            cmd.apply() ? "INVENTORY_STAGED" : "DRY_RUN",
                            LegacyInventoryPlan.VERSION,
                            fingerprint,
                            digest,
                            Long.toString(plan.count("sn_item")),
                            Long.toString(plan.count("sn_key")),
                            Long.toString(plan.count("sn_pi_counter")),
                            snapshot.manifest().issues(),
                            false,
                            false);
            try (var c = target.getConnection()) {
                if (!schema.equals(c.getCatalog())
                        || !schema.equals(stage.scalar(c, "SELECT DATABASE()")))
                    throw fail("TARGET_MISMATCH");
                if (c.getMetaData().getDatabaseMajorVersion() != 8
                        || c.getMetaData().getDatabaseMinorVersion() != 4)
                    throw fail("TARGET_VERSION");
                if (snapshot.manifest()
                        .sourceServerUuid()
                        .equals(stage.scalar(c, "SELECT @@server_uuid")))
                    throw fail("TARGET_IS_SOURCE_INSTANCE");
                if (!Set.of("16", "17")
                        .contains(
                                stage.scalar(
                                        c,
                                        "SELECT MAX(CAST(version AS UNSIGNED)) FROM"
                                                + " flyway_schema_history WHERE success=1")))
                    throw fail("TARGET_MIGRATION_VERSION");
                stage.update(c, "SET SESSION time_zone='+00:00'");
                stage.update(c, "SET SESSION innodb_lock_wait_timeout=5");
                String lock =
                        "adtek:legacy:"
                                + LegacySnapshotPackage.sha(schema.getBytes(StandardCharsets.UTF_8))
                                        .substring(0, 40);
                if (!"1".equals(stage.scalar(c, "SELECT GET_LOCK(?,0)", lock))) throw fail("BUSY");
                try {
                    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    c.setAutoCommit(false);
                    master.verifyImported(c, cmd, snapshot, main);
                    String previous =
                            stage.scalar(
                                    c,
                                    "SELECT plan_digest FROM sn_legacy_inventory_run WHERE"
                                            + " run_id=?",
                                    cmd.runId());
                    if (previous != null) {
                        if (!previous.equals(digest)
                                || !"1"
                                        .equals(
                                                stage.scalar(
                                                        c,
                                                        "SELECT COUNT(*) FROM"
                                                                + " sn_legacy_inventory_run WHERE"
                                                                + " run_id=? AND mapping_version=? AND"
                                                                + " source_fingerprint=? AND"
                                                                + " master_digest=?",
                                                        cmd.runId(),
                                                        LegacyInventoryPlan.VERSION,
                                                        fingerprint,
                                                        masterDigest)))
                            throw fail("INVENTORY_PLAN_CONFLICT");
                        verify(c, cmd.runId(), plan);
                        c.rollback();
                        return result;
                    }
                    if (!"0"
                            .equals(
                                    stage.scalar(
                                            c, "SELECT COUNT(*) FROM sn_legacy_inventory_run")))
                        throw fail("TARGET_NOT_EMPTY");
                    rows.requireNoBusiness(c);
                    com.adtek.framework.task.LegacyTaskGuard.requireEmpty(c);
                    for (String table : rows.tables())
                        if (rows.count(c, table) != 0) throw fail("TARGET_NOT_EMPTY");
                    if (!"0"
                            .equals(
                                    stage.scalar(
                                            c,
                                            "SELECT COUNT(*) FROM sn_legacy_id_map WHERE run_id=?"
                                                    + " AND target_table IN"
                                                    + " ('sn_item','sn_key','sn_pi_counter')",
                                            cmd.runId()))) throw fail("INVENTORY_MAP_CONFLICT");
                    if (!cmd.apply()) {
                        c.rollback();
                        return result;
                    }
                    rows.insertMany(c, plan.rows());
                    rows.insertMappings(c, cmd.runId(), plan.rows());
                    verify(c, cmd.runId(), plan);
                    stage.update(
                            c,
                            "INSERT INTO"
                                    + " sn_legacy_inventory_run(run_id,mapping_version,source_fingerprint,master_digest,plan_digest,result)"
                                    + " VALUES(?,?,?,?,?,?)",
                            cmd.runId(),
                            LegacyInventoryPlan.VERSION,
                            fingerprint,
                            masterDigest,
                            digest,
                            json.writeValueAsString(result));
                    c.commit();
                    return result;
                } finally {
                    if (!c.getAutoCommit()) {
                        c.rollback();
                        c.setAutoCommit(true);
                    }
                    stage.scalar(c, "SELECT RELEASE_LOCK(?)", lock);
                }
            }
        } catch (SQLException error) {
            throw fail("INVENTORY_SQL_" + error.getErrorCode());
        } catch (IOException error) {
            throw fail("FILE_IO");
        }
    }

    private void verify(Connection c, String run, LegacyInventoryPlan plan) throws SQLException {
        for (String table : rows.tables())
            if (rows.count(c, table) != plan.count(table)) throw fail("INVENTORY_TARGET_CHANGED");
        rows.verifyMany(c, plan.rows());
        rows.verifyMappings(c, run, plan.rows());
        if (Long.parseLong(
                        stage.scalar(
                                c,
                                "SELECT COUNT(*) FROM sn_legacy_id_map WHERE run_id=? AND"
                                        + " target_table IN ('sn_item','sn_key','sn_pi_counter')",
                                run))
                != plan.rows().size()) throw fail("INVENTORY_MAP_CONFLICT");
    }

    private static IllegalStateException fail(String code) {
        return new IllegalStateException("LEGACY_" + code);
    }
}
