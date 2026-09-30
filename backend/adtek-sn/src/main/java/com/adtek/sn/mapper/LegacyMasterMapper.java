package com.adtek.sn.mapper;

import com.adtek.sn.dto.LegacyTargetRowDTO;

import tools.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.*;
import java.util.*;

/** 主数据表名/字段封闭；所有值参数化，写入及逐字段核对由同一外部事务管理。 */
public final class LegacyMasterMapper {
    private static final Map<String, List<String>> MASTER_COLUMNS =
            Map.of(
                    "sn_factory",
                            List.of(
                                    "id",
                                    "code",
                                    "name",
                                    "source",
                                    "enabled",
                                    "version",
                                    "created_at",
                                    "updated_at"),
                    "sn_user_factory", List.of("user_id", "factory_id"),
                    "sn_rule",
                            List.of(
                                    "id",
                                    "code",
                                    "name",
                                    "bind_scope",
                                    "bind_value",
                                    "current_version",
                                    "revision",
                                    "created_by",
                                    "created_at",
                                    "updated_at"),
                    "sn_rule_version",
                            List.of(
                                    "id",
                                    "rule_id",
                                    "version",
                                    "prefix",
                                    "suffix",
                                    "base",
                                    "seq_length",
                                    "alphabet",
                                    "used",
                                    "content_digest",
                                    "created_by",
                                    "created_at",
                                    "updated_at"));
    private static final Map<String, List<String>> INVENTORY_COLUMNS =
            Map.of(
                    "sn_pi_counter",
                            List.of(
                                    "pi",
                                    "last_seq",
                                    "generated_qty",
                                    "imported_qty",
                                    "start_seq",
                                    "start_locked",
                                    "locked_by",
                                    "locked_at",
                                    "active_task_id"),
                    "sn_key", List.of("id", "gen_month", "pi", "sn", "seq_pi", "seq_dec"),
                    "sn_item",
                            List.of(
                                    "gen_month",
                                    "id",
                                    "sn",
                                    "pi",
                                    "seq_pi",
                                    "seq_dec",
                                    "customer",
                                    "material",
                                    "factory_id",
                                    "source",
                                    "status",
                                    "rule_version_id",
                                    "source_system",
                                    "source_account",
                                    "source_bill_id",
                                    "source_entry_id",
                                    "bill_id",
                                    "bill_no",
                                    "source_pi",
                                    "source_org_id",
                                    "source_factory_id",
                                    "task_id",
                                    "allocation_id",
                                    "created_at",
                                    "printed_at",
                                    "updated_at",
                                    "current_batch_id",
                                    "current_print_id",
                                    "acquired_at",
                                    "acquire_exception_id",
                                    "current_transfer_id",
                                    "legacy_run_id"));
    private final Map<String, List<String>> allowed;
    private final ObjectMapper json = new ObjectMapper();

    public LegacyMasterMapper() {
        this.allowed = MASTER_COLUMNS;
    }

    private LegacyMasterMapper(boolean inventory) {
        this.allowed = INVENTORY_COLUMNS;
    }

    public static LegacyMasterMapper inventory() {
        return new LegacyMasterMapper(true);
    }

    public Set<String> tables() {
        return allowed.keySet();
    }

    private List<String> columns(LegacyTargetRowDTO row) {
        var cols = allowed.get(row.table());
        if (cols == null || !row.values().keySet().equals(new HashSet<>(cols)))
            throw new IllegalArgumentException("LEGACY_TARGET_COLUMNS");
        return cols;
    }

    private List<String> keys(String table) {
        return switch (table) {
            case "sn_user_factory" -> List.of("user_id", "factory_id");
            case "sn_item" -> List.of("gen_month", "id");
            case "sn_pi_counter" -> List.of("pi");
            default -> List.of("id");
        };
    }

    public Map<String, Object> targetKey(LegacyTargetRowDTO row) {
        var key = new TreeMap<String, Object>();
        for (var column : keys(row.table())) key.put(column, row.values().get(column).toString());
        return key;
    }

    public long count(Connection c, String table) throws SQLException {
        if (!allowed.containsKey(table)) throw new IllegalArgumentException();
        try (var p = c.prepareStatement("SELECT COUNT(*) FROM " + table);
                var r = p.executeQuery()) {
            r.next();
            return r.getLong(1);
        }
    }

    public void requireNoBusiness(Connection c) throws SQLException {
        for (String table :
                List.of(
                        "sn_order_bill",
                        "sn_order_entry",
                        "sn_source_entry_quota",
                        "sn_org_mapping",
                        "sn_pi_counter",
                        "sn_item",
                        "sn_key",
                        "sn_work",
                        "sn_acquire_batch",
                        "sn_print",
                        "sn_transfer")) {
            try (var p = c.prepareStatement("SELECT COUNT(*) FROM " + table);
                    var r = p.executeQuery()) {
                r.next();
                if (r.getLong(1) != 0) throw new IllegalStateException("LEGACY_TARGET_NOT_EMPTY");
            }
        }
    }

    public void insert(Connection c, LegacyTargetRowDTO row) throws SQLException {
        var cols = columns(row);
        String sql =
                "INSERT INTO "
                        + row.table()
                        + "("
                        + String.join(",", cols)
                        + ") VALUES("
                        + String.join(",", Collections.nCopies(cols.size(), "?"))
                        + ")";
        try (var p = c.prepareStatement(sql)) {
            for (int i = 0; i < cols.size(); i++) set(p, i + 1, row.values().get(cols.get(i)));
            p.executeUpdate();
        }
    }

    /** 同一事务内每500行参数化多值INSERT，避免依赖驱动批重写开关；只有外层核对全部成功才提交。 */
    public void insertMany(Connection c, List<LegacyTargetRowDTO> source) throws SQLException {
        for (String table : allowed.keySet()) {
            var selected = source.stream().filter(r -> r.table().equals(table)).toList();
            if (selected.isEmpty()) continue;
            var cols = allowed.get(table);
            String tuple = "(" + String.join(",", Collections.nCopies(cols.size(), "?")) + ")";
            for (int start = 0; start < selected.size(); start += 500) {
                var batch = selected.subList(start, Math.min(start + 500, selected.size()));
                try (var p =
                        c.prepareStatement(
                                "INSERT INTO "
                                        + table
                                        + "("
                                        + String.join(",", cols)
                                        + ") VALUES"
                                        + String.join(
                                                ",", Collections.nCopies(batch.size(), tuple)))) {
                    p.setQueryTimeout(120);
                    int index = 1;
                    for (var row : batch) {
                        columns(row);
                        for (String column : cols) set(p, index++, row.values().get(column));
                    }
                    if (p.executeUpdate() != batch.size()) throw changed();
                }
            }
        }
    }

    /** 新隔离目标由本阶段独占整个表；一次完整扫描逐字段核对，同时拒绝额外/缺失行。 */
    public void verifyMany(Connection c, List<LegacyTargetRowDTO> source) throws SQLException {
        for (String table : allowed.keySet()) {
            var cols = allowed.get(table);
            var expected = new HashMap<List<String>, LegacyTargetRowDTO>();
            for (var row : source)
                if (row.table().equals(table)) {
                    columns(row);
                    var key =
                            keys(table).stream().map(k -> row.values().get(k).toString()).toList();
                    if (expected.put(key, row) != null) throw changed();
                }
            try (var p = c.prepareStatement("SELECT " + String.join(",", cols) + " FROM " + table);
                    var r = p.executeQuery()) {
                while (r.next()) {
                    var key = new ArrayList<String>();
                    for (String name : keys(table)) key.add(r.getString(name));
                    var row = expected.remove(key);
                    if (row == null) throw changed();
                    for (int i = 0; i < cols.size(); i++)
                        if (!Objects.equals(
                                row.values().get(cols.get(i)),
                                actual(r, i + 1, row.values().get(cols.get(i))))) throw changed();
                }
            }
            if (!expected.isEmpty()) throw changed();
        }
    }

    public void insertMappings(Connection c, String run, List<LegacyTargetRowDTO> source)
            throws SQLException {
        for (int start = 0; start < source.size(); start += 500) {
            var batch = source.subList(start, Math.min(start + 500, source.size()));
            try (var p =
                    c.prepareStatement(
                            "INSERT INTO"
                                    + " sn_legacy_id_map(run_id,source_table,source_key,"
                                    + "target_table,"
                                    + "target_key,evidence_digest)"
                                    + " VALUES"
                                    + String.join(
                                            ",",
                                            Collections.nCopies(batch.size(), "(?,?,?,?,?,?)")))) {
                p.setQueryTimeout(120);
                int index = 1;
                for (var row : batch) {
                    columns(row);
                    p.setString(index++, run);
                    p.setString(index++, row.sourceTable());
                    p.setString(index++, row.sourceKey());
                    p.setString(index++, row.table());
                    p.setString(index++, json.writeValueAsString(targetKey(row)));
                    p.setString(index++, row.evidenceDigest());
                }
                if (p.executeUpdate() != batch.size()) throw changed();
            }
        }
    }

    public void verifyMappings(Connection c, String run, List<LegacyTargetRowDTO> source)
            throws SQLException {
        var expected = new HashMap<List<String>, LegacyTargetRowDTO>();
        for (var row : source) {
            columns(row);
            if (expected.put(List.of(row.sourceTable(), row.sourceKey(), row.table()), row) != null)
                throw changed();
        }
        String types = String.join(",", Collections.nCopies(allowed.size(), "?"));
        try (var p =
                c.prepareStatement(
                        "SELECT source_table,source_key,target_table,target_key,evidence_digest"
                                + " FROM sn_legacy_id_map WHERE run_id=? AND target_table IN ("
                                + types
                                + ")")) {
            p.setString(1, run);
            int index = 2;
            for (String table : allowed.keySet()) p.setString(index++, table);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    var row =
                            expected.remove(
                                    List.of(r.getString(1), r.getString(2), r.getString(3)));
                    if (row == null
                            || !json.readTree(r.getString(4))
                                    .equals(json.valueToTree(targetKey(row)))
                            || !row.evidenceDigest().equals(r.getString(5))) throw changed();
                }
            }
        }
        if (!expected.isEmpty()) throw changed();
    }

    public void verify(Connection c, LegacyTargetRowDTO row) throws SQLException {
        var cols = columns(row);
        String where =
                String.join(" AND ", keys(row.table()).stream().map(key -> key + "=?").toList());
        try (var p =
                c.prepareStatement(
                        "SELECT "
                                + String.join(",", cols)
                                + " FROM "
                                + row.table()
                                + " WHERE "
                                + where)) {
            var keys = keys(row.table());
            for (int i = 0; i < keys.size(); i++) set(p, i + 1, row.values().get(keys.get(i)));
            try (var r = p.executeQuery()) {
                if (!r.next()) throw changed();
                for (int i = 0; i < cols.size(); i++) {
                    Object expected = row.values().get(cols.get(i));
                    if (!Objects.equals(expected, actual(r, i + 1, expected))) throw changed();
                }
            }
        }
    }

    private Object actual(ResultSet r, int column, Object expected) throws SQLException {
        if (expected instanceof Instant) {
            var time = r.getObject(column, LocalDateTime.class);
            return time == null ? null : time.toInstant(ZoneOffset.UTC);
        }
        if (expected instanceof LocalDate) return r.getObject(column, LocalDate.class);
        if (expected instanceof Boolean)
            return r.getObject(column) == null ? null : r.getBoolean(column);
        if (expected instanceof Long) return r.getObject(column, Long.class);
        return r.getObject(column);
    }

    public void mapping(
            Connection c,
            String run,
            String sourceTable,
            String sourceKey,
            String targetTable,
            Map<String, Object> key,
            String evidence,
            boolean insert)
            throws SQLException {
        if (!allowed.containsKey(targetTable) && !targetTable.equals("sys_user"))
            throw new IllegalArgumentException();
        if (insert) {
            try (var p =
                    c.prepareStatement(
                            "INSERT INTO"
                                    + " sn_legacy_id_map(run_id,source_table,source_key,"
                                    + "target_table,"
                                    + "target_key,evidence_digest)"
                                    + " VALUES(?,?,?,?,?,?)")) {
                p.setString(1, run);
                p.setString(2, sourceTable);
                p.setString(3, sourceKey);
                p.setString(4, targetTable);
                p.setString(5, json.writeValueAsString(key));
                p.setString(6, evidence);
                p.executeUpdate();
            }
        } else
            try (var p =
                    c.prepareStatement(
                            "SELECT target_key,evidence_digest FROM sn_legacy_id_map WHERE run_id=?"
                                    + " AND source_table=? AND source_key=? AND target_table=?")) {
                p.setString(1, run);
                p.setString(2, sourceTable);
                p.setString(3, sourceKey);
                p.setString(4, targetTable);
                try (var r = p.executeQuery()) {
                    if (!r.next()
                            || !json.readTree(r.getString(1)).equals(json.valueToTree(key))
                            || !evidence.equals(r.getString(2))) throw changed();
                }
            }
    }

    private void set(PreparedStatement p, int index, Object value) throws SQLException {
        p.setObject(
                index,
                value instanceof Instant instant
                        ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
                        : value);
    }

    private static IllegalStateException changed() {
        return new IllegalStateException("LEGACY_MASTER_TARGET_CHANGED");
    }
}
