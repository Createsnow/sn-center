package com.adtek.system.service.support;

import com.adtek.system.vo.AuditChangeVO;

import tools.jackson.databind.*;

import java.util.*;

/** 读历史日志不默认信任旧JSON；只解释当前允许字段，未知/过大/形状不符时保留记录并提示省略。 */
public final class AuditChanges {
    private static final Set<String> FIELDS =
            Set.of(
                    "username",
                    "displayName",
                    "deptId",
                    "roleIds",
                    "factoryIds",
                    "digest",
                    "currentVersion",
                    "enabled",
                    "intervalMinutes",
                    "mustChangePassword",
                    "code",
                    "name",
                    "dataScope",
                    "factoryScope",
                    "menuIds",
                    "deptIds",
                    "parentId",
                    "menuType",
                    "permission",
                    "path",
                    "component",
                    "sortOrder",
                    "typeId",
                    "value",
                    "labelZh",
                    "labelEn",
                    "labelVi",
                    "language",
                    "source",
                    "permissions",
                    "authorizationVersion");
    private static final Set<String> IDS = Set.of("roleIds", "factoryIds", "menuIds", "deptIds");

    private AuditChanges() {}

    public record Projection(
            List<AuditChangeVO> changes, String reason, String failureCode, boolean redacted) {}

    public static Projection project(ObjectMapper json, String raw) {
        if (raw == null || raw.length() > 65536) {
            return new Projection(List.of(), null, null, true);
        }
        try {
            JsonNode root = json.readTree(raw);
            if (!root.isObject()) {
                return new Projection(List.of(), null, null, true);
            }
            List<AuditChangeVO> values = new ArrayList<>();
            boolean hidden = false;
            String reason = null;
            String code = null;
            for (String field : root.propertyNames()) {
                JsonNode value = root.get(field);
                if (field.equals("reason")
                        && value.isString()
                        && Set.of("credentialsRejected", "credentialsLocked")
                                .contains(value.asString())) {
                    reason = value.asString();
                    continue;
                }
                if (field.equals("lockStarted") && value.isBoolean()) continue;
                if (field.equals("errorCode")
                        && value.isString()
                        && value.asString().matches("internal|[1-9][0-9]{9}")) {
                    code = value.asString();
                    continue;
                }
                if (!FIELDS.contains(field)
                        || !value.isObject()
                        || value.size() != 2
                        || !value.has("before")
                        || !value.has("after")) {
                    hidden = true;
                    continue;
                }
                try {
                    values.add(
                            new AuditChangeVO(
                                    field,
                                    text(field, value.get("before")),
                                    text(field, value.get("after"))));
                } catch (IllegalArgumentException invalid) {
                    hidden = true;
                }
            }
            values.sort(Comparator.comparing(AuditChangeVO::field));
            return new Projection(List.copyOf(values), reason, code, hidden);
        } catch (RuntimeException invalid) {
            return new Projection(List.of(), null, null, true);
        }
    }

    private static String text(String field, JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        if (field.equals("mustChangePassword") && !value.isBoolean()) {
            throw new IllegalArgumentException();
        }
        if (IDS.contains(field)) {
            if (!value.isArray() || value.size() > 100) {
                throw new IllegalArgumentException();
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode item : value) {
                if (!item.isString() || !item.asString().matches("[1-9][0-9]{0,18}")) {
                    throw new IllegalArgumentException();
                }
                ids.add(item.asString());
            }
            return String.join(", ", ids);
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.asBoolean());
        }
        if (value.isString() && value.asString().length() <= 1024) {
            return value.asString();
        }
        throw new IllegalArgumentException();
    }
}
