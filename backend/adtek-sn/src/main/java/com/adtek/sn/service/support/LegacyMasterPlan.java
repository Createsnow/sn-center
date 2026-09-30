package com.adtek.sn.service.support;

import com.adtek.framework.security.dto.LegacyIdentityDTO;
import com.adtek.sn.dto.*;

import tools.jackson.databind.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 已知V1主数据转换：保留ID/原值/时区；工厂生成ID绑定源包顺序，未知历史创建者仅保留NULL与原档案。 */
public final class LegacyMasterPlan {
    public static final String VERSION = "legacy-v1-master-v1";
    private static final Set<String> TABLES =
            Set.of("sn_factory", "sn_user", "sn_rule", "sn_rule_version");
    private final ObjectMapper json = new ObjectMapper();
    private final List<LegacyTargetRowDTO> rows = new ArrayList<>();
    private final List<LegacyIdentityDTO> users = new ArrayList<>();
    private final Map<Long, LegacyStageRowDTO> userSources = new LinkedHashMap<>();
    private final Map<String, Long> actors = new HashMap<>(), factories = new HashMap<>();
    private final Map<String, List<LegacyStageRowDTO>> source = new LinkedHashMap<>();
    private final ZoneId zone;
    private int unknown;

    public LegacyMasterPlan(LegacySnapshotPackage snapshot) throws IOException, SQLException {
        zone = ZoneId.of(snapshot.manifest().sourceZone());
        long masterCount =
                snapshot.manifest().tables().stream()
                        .filter(t -> TABLES.contains(t.name()))
                        .mapToLong(t -> Long.parseLong(t.rows()))
                        .sum();
        if (masterCount > 20000) throw fail("MASTER_RESOURCE_LIMIT");
        for (var issue : snapshot.manifest().issues())
            if (Set.of(
                            "RULE_VERSION_ORPHAN",
                            "RULE_CURRENT_MISSING",
                            "RULE_INVALID_ENCODING",
                            "USER_UNSUPPORTED_IDENTITY",
                            "USER_FACTORY_ORPHAN",
                            "FACTORY_TARGET_COLLATION_CONFLICT")
                    .contains(issue.code())) throw fail("MASTER_SOURCE_BLOCKED");
        for (var table : snapshot.manifest().tables())
            if (TABLES.contains(table.name())) {
                var list = new ArrayList<LegacyStageRowDTO>();
                snapshot.rows(table, list::add);
                source.put(table.name(), list);
            }
        for (var row : source.get("sn_user")) {
            var v = node(row);
            long id = number(v, "id");
            if (actors.put(text(v, "emp_no"), id) != null) throw fail("MASTER_DUPLICATE");
            userSources.put(id, row);
        }
        long factoryId = 0;
        for (var row : source.get("sn_factory")) {
            var v = node(row);
            long id = ++factoryId;
            String code = text(v, "factory_code");
            if (code.isBlank()
                    || code.codePointCount(0, code.length()) > 64
                    || factories.put(code, id) != null) throw fail("MASTER_FACTORY_INVALID");
            String name = text(v, "factory_name"), origin = text(v, "source");
            if (name.isBlank() || !Set.of("K3", "MANUAL").contains(origin))
                throw fail("MASTER_FACTORY_INVALID");
            add(
                    "sn_factory",
                    "sn_factory",
                    row,
                    values(
                            "id",
                            id,
                            "code",
                            code,
                            "name",
                            name,
                            "source",
                            origin,
                            "enabled",
                            bool(v, "enabled"),
                            "version",
                            1L,
                            "created_at",
                            time(v, "created_at"),
                            "updated_at",
                            time(v, "updated_at")));
        }
        for (var row : source.get("sn_user")) {
            var v = node(row);
            long id = number(v, "id");
            String factory = nullable(v, "factory_code");
            Long factoryIdValue = factory == null ? null : factories.get(factory);
            if (factory != null && factoryIdValue == null) throw fail("MASTER_FACTORY_ORPHAN");
            String lang = nullable(v, "lang");
            users.add(
                    new LegacyIdentityDTO(
                            id,
                            text(v, "emp_no"),
                            text(v, "name"),
                            text(v, "password_hash"),
                            lang == null ? "zh-CN" : lang,
                            bool(v, "must_change_pwd"),
                            text(v, "role"),
                            factory != null,
                            actor(v, "created_by"),
                            time(v, "created_at"),
                            time(v, "updated_at")));
            if (factoryIdValue != null)
                add(
                        "sn_user_factory",
                        "sn_user",
                        row,
                        values("user_id", id, "factory_id", factoryIdValue));
        }
        var ruleIds = new HashSet<Long>();
        var current = new HashMap<Long, Long>();
        for (var row : source.get("sn_rule")) {
            var v = node(row);
            long id = number(v, "id"), version = number(v, "current_version");
            String code = text(v, "rule_code"),
                    name = text(v, "rule_name"),
                    scope = text(v, "bind_scope"),
                    bind = text(v, "bind_value");
            if (!ruleIds.add(id)
                    || !code.matches("[A-Za-z0-9_-]{1,64}")
                    || name.isBlank()
                    || !Set.of("GENERAL", "CUSTOMER", "PI").contains(scope)
                    || ("GENERAL".equals(scope) != bind.isEmpty()))
                throw fail("MASTER_RULE_INVALID");
            current.put(id, version);
            add(
                    "sn_rule",
                    "sn_rule",
                    row,
                    values(
                            "id",
                            id,
                            "code",
                            code,
                            "name",
                            name,
                            "bind_scope",
                            scope,
                            "bind_value",
                            bind,
                            "current_version",
                            version,
                            "revision",
                            1L,
                            "created_by",
                            actor(v, "created_by"),
                            "created_at",
                            time(v, "created_at"),
                            "updated_at",
                            time(v, "updated_at")));
        }
        var versions = new HashSet<String>();
        for (var row : source.get("sn_rule_version")) {
            var v = node(row);
            long id = number(v, "id"), rule = number(v, "rule_id"), version = number(v, "version");
            if (!ruleIds.contains(rule) || !versions.add(rule + ":" + version))
                throw fail("MASTER_RULE_ORPHAN");
            var spec =
                    new SequenceSpec(
                            text(v, "prefix"),
                            text(v, "suffix"),
                            Math.toIntExact(number(v, "base")),
                            Math.toIntExact(number(v, "seq_len")),
                            text(v, "charset"));
            String digest =
                    hash(
                            spec.prefix().length()
                                    + ":"
                                    + spec.prefix()
                                    + spec.suffix().length()
                                    + ":"
                                    + spec.suffix()
                                    + ":"
                                    + spec.base()
                                    + ":"
                                    + spec.length()
                                    + ":"
                                    + spec.alphabet());
            add(
                    "sn_rule_version",
                    "sn_rule_version",
                    row,
                    values(
                            "id",
                            id,
                            "rule_id",
                            rule,
                            "version",
                            version,
                            "prefix",
                            spec.prefix(),
                            "suffix",
                            spec.suffix(),
                            "base",
                            (long) spec.base(),
                            "seq_length",
                            (long) spec.length(),
                            "alphabet",
                            spec.alphabet(),
                            "used",
                            bool(v, "used"),
                            "content_digest",
                            digest,
                            "created_by",
                            actor(v, "created_by"),
                            "created_at",
                            time(v, "created_at"),
                            "updated_at",
                            time(v, "updated_at")));
        }
        for (var e : current.entrySet())
            if (!versions.contains(e.getKey() + ":" + e.getValue()))
                throw fail("MASTER_CURRENT_RULE_MISSING");
    }

    private void add(
            String table, String sourceTable, LegacyStageRowDTO row, Map<String, Object> values) {
        rows.add(
                new LegacyTargetRowDTO(
                        table,
                        sourceTable,
                        row.key(),
                        hash(VERSION + "\n" + row.digest()),
                        Collections.unmodifiableMap(values)));
    }

    private JsonNode node(LegacyStageRowDTO row) {
        return json.readTree(row.payload());
    }

    private String text(JsonNode v, String field) {
        String value = nullable(v, field);
        if (value == null || value.codePoints().anyMatch(Character::isISOControl))
            throw fail("MASTER_FIELD_INVALID");
        return value;
    }

    private String nullable(JsonNode v, String field) {
        var value = v.get(field);
        if (value == null) throw fail("MASTER_FIELD_INVALID");
        return value.isNull() ? null : value.asString();
    }

    private long number(JsonNode v, String field) {
        String s = text(v, field);
        if (!s.matches("[1-9][0-9]{0,18}")) throw fail("MASTER_NUMBER_INVALID");
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw fail("MASTER_NUMBER_INVALID");
        }
    }

    private boolean bool(JsonNode v, String field) {
        String s = text(v, field);
        if (!Set.of("0", "1").contains(s)) throw fail("MASTER_BOOLEAN_INVALID");
        return "1".equals(s);
    }

    private Long actor(JsonNode v, String field) {
        String name = nullable(v, field);
        if (name == null || name.isEmpty()) return null;
        Long id = actors.get(name);
        if (id == null) unknown++;
        return id;
    }

    private Instant time(JsonNode v, String field) {
        try {
            LocalDateTime time =
                    LocalDateTime.parse(
                            text(v, field).replace(' ', 'T'),
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            var offsets = zone.getRules().getValidOffsets(time);
            if (offsets.size() != 1) throw fail("TIME_AMBIGUOUS");
            return time.toInstant(offsets.getFirst());
        } catch (DateTimeException e) {
            throw fail("MASTER_TIME_INVALID");
        }
    }

    private static Map<String, Object> values(Object... pairs) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    private static String hash(String value) {
        return LegacySnapshotPackage.sha(value.getBytes(StandardCharsets.UTF_8));
    }

    public List<LegacyTargetRowDTO> rows() {
        return List.copyOf(rows);
    }

    public List<LegacyIdentityDTO> users() {
        return List.copyOf(users);
    }

    public Map<Long, LegacyStageRowDTO> userSources() {
        return Collections.unmodifiableMap(userSources);
    }

    public long count(String table) {
        return rows.stream().filter(r -> r.table().equals(table)).count();
    }

    public int unknownActors() {
        return unknown;
    }

    public String digest(String sourceFingerprint) {
        return hash(json.writeValueAsString(List.of(VERSION, sourceFingerprint, rows, users)));
    }

    private static IllegalStateException fail(String code) {
        return new IllegalStateException("LEGACY_" + code);
    }
}
