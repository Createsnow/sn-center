package com.adtek.system.mapper;

import com.adtek.framework.security.dto.LegacyIdentityDTO;
import java.sql.*;
import java.util.*;

/** 仅供离线迁移工厂构造的JDBC Mapper；封闭身份表，不进入普通Web授权/Mapper扫描路径。 */
public final class LegacyIdentityMapper {
    public void lock(Connection c)throws SQLException{try(var p=c.prepareStatement("SELECT initialized FROM sys_security_control WHERE id=1 FOR UPDATE");var r=p.executeQuery()){if(!r.next())throw new IllegalStateException("LEGACY_IDENTITY_CONTROL_MISSING");}}
    public long count(Connection c,String table)throws SQLException{
        if(!Set.of("sys_user","sys_user_role","sys_user_factory").contains(table))throw new IllegalArgumentException();
        try(var p=c.prepareStatement("SELECT COUNT(*) FROM "+table);var r=p.executeQuery()){r.next();return r.getLong(1);}
    }
    public boolean initialized(Connection c)throws SQLException{try(var p=c.prepareStatement("SELECT initialized FROM sys_security_control WHERE id=1");var r=p.executeQuery()){r.next();return r.getBoolean(1);}}
    public void initialize(Connection c)throws SQLException{try(var p=c.prepareStatement("UPDATE sys_security_control SET initialized=TRUE WHERE id=1")){if(p.executeUpdate()!=1)throw new IllegalStateException("LEGACY_IDENTITY_CONTROL_MISSING");}}
    public void roleBaseline(Connection c)throws SQLException{
        var expected=Map.of(1L,"PLATFORM_ADMIN",2L,"SN_HQ",3L,"FACTORY_OPERATOR",4L,"SN_QUERY_ALL",5L,"SN_QUERY_FACTORY");
        try(var p=c.prepareStatement("SELECT id,role_code,enabled FROM sys_role WHERE id BETWEEN 1 AND 5");var r=p.executeQuery()){
            int n=0;while(r.next()){n++;if(!r.getBoolean(3)||!Objects.equals(expected.get(r.getLong(1)),r.getString(2)))throw new IllegalStateException("LEGACY_ROLE_BASELINE_CHANGED");}if(n!=5)throw new IllegalStateException("LEGACY_ROLE_BASELINE_CHANGED");
        }
    }
    public void insert(Connection c,LegacyIdentityDTO u,List<Long> roles)throws SQLException{
        try(var p=c.prepareStatement("INSERT INTO sys_user(id,username,password_hash,display_name,dept_id,enabled,must_change_password,security_version,create_by,create_time,update_by,update_time,language,principal_type) VALUES(?,?,?,?,NULL,FALSE,?,1,?,?,NULL,?,?,'USER')")){
            p.setLong(1,u.id());p.setString(2,u.username());p.setString(3,"{pbkdf2-sha256-120k}"+u.legacyPasswordHash());p.setString(4,u.displayName());p.setBoolean(5,u.mustChangePassword());p.setObject(6,u.createdBy());p.setObject(7,java.time.LocalDateTime.ofInstant(u.createdAt(),java.time.ZoneOffset.UTC));p.setObject(8,java.time.LocalDateTime.ofInstant(u.updatedAt(),java.time.ZoneOffset.UTC));p.setString(9,u.language());p.executeUpdate();
        }
        try(var p=c.prepareStatement("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)")){for(long role:roles){p.setLong(1,u.id());p.setLong(2,role);p.addBatch();}p.executeBatch();}
    }
    public void verify(Connection c,LegacyIdentityDTO u,List<Long> roles)throws SQLException{
        try(var p=c.prepareStatement("SELECT username,password_hash,display_name,dept_id,enabled,must_change_password,security_version,create_by,create_time,update_by,update_time,language,principal_type FROM sys_user WHERE id=?")){
            p.setLong(1,u.id());try(var r=p.executeQuery()){
                if(!r.next()||!u.username().equals(r.getString(1))||!("{pbkdf2-sha256-120k}"+u.legacyPasswordHash()).equals(r.getString(2))||!u.displayName().equals(r.getString(3))||r.getObject(4)!=null||r.getBoolean(5)||r.getBoolean(6)!=u.mustChangePassword()||r.getLong(7)!=1||!Objects.equals(u.createdBy(),r.getObject(8,Long.class))||!u.createdAt().equals(r.getObject(9,java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC))||r.getObject(10)!=null||!u.updatedAt().equals(r.getObject(11,java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC))||!u.language().equals(r.getString(12))||!"USER".equals(r.getString(13)))throw new IllegalStateException("LEGACY_IDENTITY_TARGET_CHANGED");
            }
        }
        try(var p=c.prepareStatement("SELECT role_id FROM sys_user_role WHERE user_id=? ORDER BY role_id")){p.setLong(1,u.id());try(var r=p.executeQuery()){var actual=new ArrayList<Long>();while(r.next())actual.add(r.getLong(1));if(!actual.equals(roles))throw new IllegalStateException("LEGACY_IDENTITY_TARGET_CHANGED");}}
    }
}
