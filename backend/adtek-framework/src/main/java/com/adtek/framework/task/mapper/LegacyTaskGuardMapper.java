package com.adtek.framework.task.mapper;

import java.sql.*;
import java.util.List;

/** 离线迁移新目标验证，框架表检查仍由框架持有，不让业务模块拼接其SQL。 */
public final class LegacyTaskGuardMapper {
    public void requireEmpty(Connection c) throws SQLException {
        for (String table :
                List.of("sys_task", "sys_operation", "sys_file", "sys_audit_export_row"))
            try (var p = c.prepareStatement("SELECT COUNT(*) FROM " + table);
                    var r = p.executeQuery()) {
                r.next();
                if (r.getLong(1) != 0) throw new IllegalStateException("LEGACY_TARGET_NOT_EMPTY");
            }
    }
}
