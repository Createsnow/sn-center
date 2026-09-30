package com.adtek.system.service.impl;

import com.adtek.framework.security.LegacyIdentityPort;
import com.adtek.framework.security.dto.LegacyIdentityDTO;
import com.adtek.system.mapper.LegacyIdentityMapper;
import java.sql.*;
import java.util.*;

/** 不装配成Web服务；只由离线入口工厂构造，角色解释由system拥有。 */
public final class LegacyIdentityServiceImpl implements LegacyIdentityPort {
    private final LegacyIdentityMapper rows;
    public LegacyIdentityServiceImpl(LegacyIdentityMapper rows){this.rows=rows;}
    private static List<Long> roles(LegacyIdentityDTO u){return switch(u.legacyRole()){case "admin"->List.of(1L,2L);case "factory_operator"->List.of(3L);case "query"->List.of(u.hasFactory()?5L:4L);default->throw new IllegalStateException("LEGACY_IDENTITY_UNSUPPORTED");};}
    @Override public void validate(List<LegacyIdentityDTO> users){
        var ids=new HashSet<Long>();var names=new HashSet<String>();
        for(var u:users){
            if(u.id()<=0||!ids.add(u.id())||u.username()==null||!u.username().matches("[A-Za-z0-9_-]{2,32}")||!names.add(u.username())||u.displayName()==null||u.displayName().isBlank()||u.displayName().length()>100||u.legacyPasswordHash()==null||!u.legacyPasswordHash().matches("[0-9a-f]{96}")||!Set.of("zh-CN","en","vi").contains(u.language())||u.createdAt()==null||u.updatedAt()==null||("factory_operator".equals(u.legacyRole())&&!u.hasFactory())||("admin".equals(u.legacyRole())&&u.hasFactory()))throw new IllegalStateException("LEGACY_IDENTITY_UNSUPPORTED");
            roles(u);
        }
        for(var u:users)if(u.createdBy()!=null&&!ids.contains(u.createdBy()))throw new IllegalStateException("LEGACY_IDENTITY_ACTOR_ORPHAN");
    }
    @Override public void lock(Connection c)throws SQLException{if(c.getAutoCommit())throw new IllegalStateException("LEGACY_TRANSACTION_REQUIRED");rows.lock(c);rows.roleBaseline(c);}
    @Override public void requireEmpty(Connection c)throws SQLException{if(rows.initialized(c)||rows.count(c,"sys_user")!=0||rows.count(c,"sys_user_role")!=0||rows.count(c,"sys_user_factory")!=0)throw new IllegalStateException("LEGACY_TARGET_NOT_EMPTY");}
    @Override public void insert(Connection c,List<LegacyIdentityDTO> users)throws SQLException{validate(users);requireEmpty(c);for(var u:users)rows.insert(c,u,roles(u));rows.initialize(c);}
    @Override public void verify(Connection c,List<LegacyIdentityDTO> users)throws SQLException{
        if(!rows.initialized(c)||rows.count(c,"sys_user")!=users.size()||rows.count(c,"sys_user_role")!=users.stream().mapToLong(u->roles(u).size()).sum()||rows.count(c,"sys_user_factory")!=0)throw new IllegalStateException("LEGACY_IDENTITY_TARGET_CHANGED");
        for(var u:users)rows.verify(c,u,roles(u));
    }
}
