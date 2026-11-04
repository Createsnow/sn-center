package com.adtek.framework.mybatis;

import java.util.Map;
import java.util.Set;

/** 业务模块登记精确 statement ID，基础层不认识业务表；未登记语句一律拒绝。 */
public interface DataPermissionRules {
    Map<String, Rule> statements();

    record TableRule(String departmentColumn, String ownerColumn, String resourceColumn) {
        public TableRule(String departmentColumn, String ownerColumn) {
            this(departmentColumn, ownerColumn, null);
        }

        public TableRule {
            if (resourceColumn == null
                    ? (!departmentColumn.matches("[a-z_]+") || !ownerColumn.matches("[a-z_]+"))
                    : !resourceColumn.matches("[a-z_]+")) {
                throw new IllegalArgumentException("Invalid scope columns");
            }
        }
    }

    record Rule(
            String resource,
            Map<String, TableRule> tables,
            Set<String> permissions,
            String exemptionReason) {
        public Rule {
            tables = Map.copyOf(tables);
            permissions = Set.copyOf(permissions);
        }

        public static Rule scoped(
                String resource,
                String table,
                String department,
                String owner,
                String... permissions) {
            if (permissions.length == 0) {
                throw new IllegalArgumentException(
                        "Scoped statement requires permission whitelist");
            }
            return new Rule(
                    resource,
                    Map.of(table, new TableRule(department, owner)),
                    Set.of(permissions),
                    null);
        }

        public static Rule internal(String reason) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Exemption requires a reason");
            }
            return new Rule(null, Map.of(), Set.of(), reason);
        }

        /** 业务资源范围独立于部门；空绑定无数据，业务资源为空的来源仅全部业务资源资格可读。 */
        public static Rule resources(
                String resource, String table, String resourceColumn, String... permissions) {
            if (permissions.length == 0) {
                throw new IllegalArgumentException("Resource scope requires permissions");
            }
            return new Rule(
                    resource,
                    Map.of(table, new TableRule(null, null, resourceColumn)),
                    Set.of(permissions),
                    null);
        }
    }
}
