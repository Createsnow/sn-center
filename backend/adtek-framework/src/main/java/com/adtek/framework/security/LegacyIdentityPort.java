package com.adtek.framework.security;

import com.adtek.framework.security.dto.LegacyIdentityDTO;
import java.sql.*;
import java.util.List;

/**
 * 离线导入的身份所有权边界，不接受页面调用。调用方必须已验证目标白名单、新目标、来源摘要并持迁移互斥。
 * 同一显式JDBC事务锁住安全协调行；写入全体停用账号、原待改密/语言/旧哈希及确定的旧角色映射。
 * 不提交事务、不启用账号、不重置密码；重复执行必须逐字段核验，任何差异整体回滚。
 */
public interface LegacyIdentityPort {
    void validate(List<LegacyIdentityDTO> users);
    void lock(Connection connection)throws SQLException;
    void requireEmpty(Connection connection)throws SQLException;
    void insert(Connection connection,List<LegacyIdentityDTO> users)throws SQLException;
    void verify(Connection connection,List<LegacyIdentityDTO> users)throws SQLException;
}
