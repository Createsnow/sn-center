package com.adtek.system.service.impl;

import com.adtek.framework.mybatis.DataPermissionRules;

import org.springframework.stereotype.Component;

import java.util.*;

/** 豁免仅限列出的内部认证/协调/审计语句；新增 Mapper 方法不会自动继承豁免。 */
@Component
public class SystemDataPermissionRules implements DataPermissionRules {
    @Override
    public Map<String, Rule> statements() {
        Map<String, Rule> rules = new HashMap<>();
        internal(
                rules,
                "ServiceClientMapper",
                "独立MES身份：管理须当前全局安全权限与工厂委派，认证按精确clientId；秘密不进入普通查询",
                "client",
                "byUser",
                "permissions",
                "create",
                "rotate",
                "clear",
                "grant",
                "updateClient",
                "insert");
        internal(
                rules,
                "AuditExportMapper",
                "固定导出快照：提交来自两项权限范围交集，读取前TaskAccess验证本人及完整范围；不开放Mapper HTTP入口",
                "insert",
                "page",
                "count");
        rules.put(
                "com.adtek.system.mapper.AuditExportMapper.visibleCount",
                Rule.scoped(
                        "system:audit-export",
                        "sys_audit_export_row",
                        "actor_dept_id",
                        "user_id",
                        "system:audit:list",
                        "system:audit:export"));
        for (String mapper :
                List.of(
                        "RoleMapper",
                        "DepartmentMapper",
                        "ManagedMenuMapper",
                        "DictionaryTypeMapper",
                        "DictionaryItemMapper")) {
            internal(
                    rules,
                    mapper,
                    "全局配置无所属部门；服务强制对应权限和 security:administer 均为全部部门范围，写入持安全协调锁",
                    "list",
                    "find",
                    "insert",
                    "update");
        }
        internal(
                rules,
                "ConfigurationSecurityMapper",
                "仅供全局配置安全事务装配关联、锁定账号及撤销版本",
                "lockUsers",
                "roleMenus",
                "roleDepartments",
                "roleUsers",
                "menuUsers",
                "clearMenus",
                "clearDepartments",
                "addMenu",
                "addDepartment",
                "invalidate",
                "enabledDepartmentUsers");
        internal(
                rules,
                "IdentityMapper",
                "认证及当前授权装配，按已验证账号/主体 ID 查询",
                "recordLogin",
                "findByUsername",
                "findById",
                "lockById",
                "findRolePermissions",
                "findCustomDepartments",
                "findDepartmentTree",
                "changePassword",
                "upgradePasswordHash",
                "updateLanguage");
        internal(
                rules,
                "BootstrapMapper",
                "非 Web 单次初始化，持有初始化锁",
                "lockInitialization",
                "countUsers",
                "assignInitialRoles",
                "markOwnership",
                "finishInitialization",
                "insert");
        internal(
                rules,
                "OperLogMapper",
                "只追加经过白名单脱敏的审计事实，按可信主体读取非秘密身份快照",
                "insert",
                "management",
                "actorSnapshot",
                "requestFailure");
        for (String method :
                List.of("selectList", "selectById", "selectCount", "actions", "modules")) {
            rules.put(
                    "com.adtek.system.mapper.AuditQueryMapper." + method,
                    Rule.scoped(
                            "system:audit",
                            "sys_oper_log",
                            "actor_dept_id",
                            "user_id",
                            "system:audit:list"));
        }
        internal(
                rules,
                "UserSecurityMapper",
                "用户管理内部授权策略；对外选项由服务按当前权限过滤",
                "roleCodes",
                "usersWithRoles",
                "enabledRoleId",
                "creatorName",
                "lockControl",
                "roleIds",
                "departmentName",
                "departments",
                "roles",
                "enabledDepartment",
                "enabledRole",
                "clearRoles",
                "addRole",
                "enabledAdministrators",
                "menus");
        for (String method :
                List.of("selectList", "selectById", "selectCount", "insert", "update", "lock")) {
            String[] permissions =
                    switch (method) {
                        case "insert" -> new String[] {"system:user:add"};
                        case "update", "lock" ->
                                new String[] {
                                    "system:user:update", "system:user:status", "system:user:reset"
                                };
                        default -> new String[] {"system:user:list"};
                    };
            rules.put(
                    "com.adtek.system.mapper.UserMapper." + method,
                    Rule.scoped("system:user", "sys_user", "dept_id", "id", permissions));
        }
        return Map.copyOf(rules);
    }

    private void internal(
            Map<String, Rule> target, String mapper, String reason, String... methods) {
        for (String method : methods) {
            target.put("com.adtek.system.mapper." + mapper + "." + method, Rule.internal(reason));
        }
    }
}
