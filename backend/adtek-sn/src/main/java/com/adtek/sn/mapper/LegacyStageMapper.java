package com.adtek.sn.mapper;

import com.adtek.sn.dto.LegacyStageRowDTO;

import java.sql.*;
import java.util.*;

/** 独立运维JDBC，仅封闭staging表；行与检查点由调用方同一连接事务提交，不进入页面MapperScan。 */
public final class LegacyStageMapper {
    public record Run(
            String fingerprint, String manifest, String mapping, String state, String result) {}

    public record Checkpoint(String digest, long sourceRows, long ordinal) {}

    public record StoredRow(String key, String payload, String digest) {}

    public Run run(Connection c, String id) throws SQLException {
        try (var p =
                        prepare(
                                c,
                                "SELECT"
                                        + " source_fingerprint,manifest_digest,"
                                        + "mapping_version,state,result"
                                        + " FROM sn_legacy_migration_run WHERE run_id=?",
                                id);
                var r = p.executeQuery()) {
            return r.next()
                    ? new Run(
                            r.getString(1),
                            r.getString(2),
                            r.getString(3),
                            r.getString(4),
                            r.getString(5))
                    : null;
        }
    }

    public Checkpoint checkpoint(Connection c, String run, String table) throws SQLException {
        try (var p =
                        prepare(
                                c,
                                "SELECT source_digest,source_rows,last_ordinal FROM"
                                        + " sn_legacy_migration_table WHERE run_id=? AND"
                                        + " table_name=?",
                                run,
                                table);
                var r = p.executeQuery()) {
            return r.next() ? new Checkpoint(r.getString(1), r.getLong(2), r.getLong(3)) : null;
        }
    }

    public Map<Long, StoredRow> existing(Connection c, String run, String table, long from, long to)
            throws SQLException {
        var found = new HashMap<Long, StoredRow>();
        try (var p =
                        prepare(
                                c,
                                "SELECT ordinal,row_key,CAST(payload AS CHAR),payload_digest FROM"
                                        + " sn_legacy_row WHERE run_id=? AND table_name=?"
                                        + " AND ordinal"
                                        + " BETWEEN ? AND ? ORDER BY ordinal",
                                run,
                                table,
                                from,
                                to);
                var r = p.executeQuery()) {
            while (r.next())
                found.put(
                        r.getLong(1),
                        new StoredRow(r.getString(2), r.getString(3), r.getString(4)));
        }
        return found;
    }

    public void insert(Connection c, String run, String table, List<LegacyStageRowDTO> rows)
            throws SQLException {
        if (rows.isEmpty()) return;
        try (var p =
                prepare(
                        c,
                        "INSERT INTO"
                                + " sn_legacy_row(run_id,table_name,ordinal,"
                                + "row_key,payload,payload_digest)"
                                + " VALUES(?,?,?,?,?,?)")) {
            for (var row : rows) {
                p.setString(1, run);
                p.setString(2, table);
                p.setLong(3, row.ordinal());
                p.setString(4, row.key());
                p.setString(5, row.payload());
                p.setString(6, row.digest());
                p.addBatch();
            }
            p.executeBatch();
        }
    }

    public long count(Connection c, String run, String table) throws SQLException {
        return Long.parseLong(
                scalar(
                        c,
                        "SELECT COUNT(*) FROM sn_legacy_row WHERE run_id=? AND table_name=?",
                        run,
                        table));
    }

    public long maximum(Connection c, String run, String table) throws SQLException {
        return Long.parseLong(
                scalar(
                        c,
                        "SELECT COALESCE(MAX(ordinal),0) FROM sn_legacy_row WHERE run_id=? AND"
                                + " table_name=?",
                        run,
                        table));
    }

    public String scalar(Connection c, String sql, Object... values) throws SQLException {
        try (var p = prepare(c, sql, values);
                var r = p.executeQuery()) {
            return r.next() ? r.getString(1) : null;
        }
    }

    public int update(Connection c, String sql, Object... values) throws SQLException {
        try (var p = prepare(c, sql, values)) {
            return p.executeUpdate();
        }
    }

    private PreparedStatement prepare(Connection c, String sql, Object... values)
            throws SQLException {
        var p = c.prepareStatement(sql);
        p.setQueryTimeout(120);
        for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
        return p;
    }
}
