package com.adtek.system.service.support;

import com.adtek.common.core.*;
import com.adtek.framework.security.*;
import com.adtek.system.mapper.*;
import java.util.Map;
import org.springframework.stereotype.Component;
import static com.adtek.system.constant.ErrorCodeConstants.*;

/** 角色/目录/部门/字典是全局配置，无所属部门；不把局部业务管理权限提升为配置管理权限。 */
@Component
public class ConfigurationPolicy {
    public static final String ADMIN = "system:security:administer";
    public static final Map<String,String> COMPONENTS = Map.ofEntries(
        Map.entry("system.users", "/system/users"), Map.entry("system.roles", "/system/roles"),
        Map.entry("system.departments", "/system/departments"), Map.entry("system.menus", "/system/menus"),
        Map.entry("system.dictionaries", "/system/dictionaries"), Map.entry("system.audit", "/system/audit"),
        Map.entry("sn.factories", "/factories"), Map.entry("sn.orders", "/orders"), Map.entry("sn.rules", "/rules"),
        Map.entry("sn.pi-init", "/pi-init"), Map.entry("sn.generate", "/generate"), Map.entry("sn.acquire", "/acquire"), Map.entry("sn.transfer", "/transfer"),
        Map.entry("sn.audit", "/audit"), Map.entry("sn.query", "/query"), Map.entry("sn.dashboard", "/dashboard"));
    private final CurrentActor actors;
    private final UserSecurityMapper security;
    private final IdentityMapper identities;
    private final AuthorizationPort authorization;
    public ConfigurationPolicy(CurrentActor actors, UserSecurityMapper security, IdentityMapper identities, AuthorizationPort authorization) {
        this.actors=actors; this.security=security; this.identities=identities; this.authorization=authorization;
    }
    public Actor read(String permission) {
        Actor actor=actors.requirePermission(permission);
        requireGlobal(actor, permission);
        return actor;
    }
    /** 必须在 READ_COMMITTED 事务内调用，锁顺序与用户管理完全一致。 */
    public Actor lock(String permission) {
        Actor requested=read(permission);
        if(security.lockControl()==null) { throw new IllegalStateException("Missing security coordination row"); }
        var user=identities.lockById(requested.userId());
        if(user==null || !user.getEnabled() || user.getSecurityVersion()!=requested.securityVersion()) { throw new BusinessException(ACCOUNT_CHANGED); }
        Actor current=authorization.loadCurrent(requested.userId()).orElseThrow(()->new BusinessException(ACCOUNT_CHANGED));
        requireGlobal(current,permission);
        return current;
    }
    private void requireGlobal(Actor actor,String permission) {
        var grant=actor.grants().get(permission);
        var admin=actor.grants().get(ADMIN);
        if(actor.mustChangePassword() || grant==null || admin==null || !grant.departments().all() || !admin.departments().all()) { throw new BusinessException(GRANT_EXCEEDED); }
    }
    public static void dominates(Actor actor, Actor target, Long department) {
        for(var entry:target.grants().entrySet()) {
            var own=actor.grants().get(entry.getKey()); var requested=entry.getValue();
            if(own==null) { throw new BusinessException(GRANT_EXCEEDED); }
            var allowed=own.departments(); var actual=requested.departments();
            if(!allowed.all() && (actual.all() || !allowed.departmentIds().containsAll(actual.departmentIds())
                || (actual.self() && !allowed.permits(actor.userId(),target.userId(),department)))) { throw new BusinessException(GRANT_EXCEEDED); }
            if(!own.allFactories() && (requested.allFactories() || !own.factoryIds().containsAll(requested.factoryIds()))) { throw new BusinessException(GRANT_EXCEEDED); }
        }
    }
    public static long id(String value) {
        try { if(value==null || !value.matches("[1-9][0-9]{0,18}")) { throw invalid(); } return Long.parseLong(value); }
        catch(NumberFormatException error) { throw invalid(); }
    }
    public static BusinessException invalid() { return new BusinessException(com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT); }
}
