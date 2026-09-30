package com.adtek.system.service.impl;

import com.adtek.common.annotation.OperLog;
import com.adtek.common.core.*;
import com.adtek.framework.audit.AuditContext;
import com.adtek.framework.security.CommandValidator;
import com.adtek.system.convert.ConfigurationConvert;
import com.adtek.system.domain.*;
import com.adtek.system.dto.*;
import com.adtek.system.mapper.*;
import com.adtek.system.service.*;
import com.adtek.system.service.support.ConfigurationPolicy;
import com.adtek.system.vo.*;
import java.util.*;
import java.util.function.IntSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.adtek.system.constant.ErrorCodeConstants.*;
import static com.adtek.system.service.support.ConfigurationPolicy.*;
import static com.adtek.common.annotation.OperLog.BusinessType.UPDATE;

/** 配置写与关系更新持同一协调锁；授权前后快照比较和安全版本更新均在同一事务内。 */
@Service
public class ConfigurationManagementServiceImpl implements ConfigurationManagementService {
    private final RoleMapper roles;
    private final DepartmentMapper departments;
    private final ManagedMenuMapper menus;
    private final DictionaryTypeMapper types;
    private final DictionaryItemMapper items;
    private final ConfigurationSecurityMapper security;
    private final UserSecurityMapper users;
    private final SystemAuthorizationService authorization;
    private final ConfigurationPolicy policy;
    private final CommandValidator validator;
    private final ConfigurationConvert convert;
    public ConfigurationManagementServiceImpl(RoleMapper roles, DepartmentMapper departments, ManagedMenuMapper menus,
            DictionaryTypeMapper types, DictionaryItemMapper items, ConfigurationSecurityMapper security,
            UserSecurityMapper users, SystemAuthorizationService authorization, ConfigurationPolicy policy,
            CommandValidator validator, ConfigurationConvert convert) {
        this.roles=roles; this.departments=departments; this.menus=menus; this.types=types; this.items=items;
        this.security=security; this.users=users; this.authorization=authorization; this.policy=policy;
        this.validator=validator; this.convert=convert;
    }

    @Override @Transactional(readOnly=true)
    public List<RoleVO> roles() {
        policy.read("system:role:list");
        return bounded(roles.list()).stream().map(r->convert.toRole(r,text(security.roleMenus(r.getId())),text(security.roleDepartments(r.getId())))).toList();
    }
    @Override @Transactional(readOnly=true)
    public List<DepartmentVO> departments() {
        policy.read("system:dept:list"); return bounded(departments.list()).stream().map(convert::toDepartment).toList();
    }
    @Override @Transactional(readOnly=true)
    public List<ManagedMenuVO> menus() {
        policy.read("system:menu:list"); return bounded(menus.list()).stream().map(convert::toManagedMenu).toList();
    }
    @Override @Transactional(readOnly=true)
    public List<DictionaryTypeVO> dictionaryTypes() {
        policy.read("system:dict:list"); return bounded(types.list()).stream().map(convert::toDictionaryType).toList();
    }
    @Override @Transactional(readOnly=true)
    public List<DictionaryItemVO> dictionaryItems(String typeId) {
        policy.read("system:dict:list"); found(types.find(id(typeId)));
        return bounded(items.list(id(typeId))).stream().map(convert::toDictionaryItem).toList();
    }

    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="角色管理",action="system:role:update",businessType=UPDATE,
        fields={"code","name","enabled","dataScope","factoryScope","menuIds","deptIds"})
    public ConfigurationChangeVO saveRole(String targetId, RoleSaveDTO command) {
        if(targetId==null) { throw invalid(); }
        return applyRole(targetId,command);
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="系统配置",action="system:role:update",businessType=com.adtek.common.annotation.OperLog.BusinessType.INSERT,
        fields={"code","name","dataScope","factoryScope","enabled","menuIds","deptIds"})
    public ConfigurationChangeVO createRole(RoleSaveDTO command) { return applyRole(null,command); }
    private ConfigurationChangeVO applyRole(String targetId, RoleSaveDTO command) {
        validator.requireValid(command);
        Actor actor=policy.lock("system:role:update");
        var beforeUsers=snapshotUsers();
        RoleEntity before=targetId==null?null:found(roles.find(id(targetId)));
        version(before==null?null:before.getVersion(),command.version());
        if(before==null) { capacity(roles.list()); }
        else if(!before.getCode().equals(command.code())) { throw invalid(); }
        var menuIds=ids(command.menuIds()); var deptIds=ids(command.deptIds());
        if(command.dataScope()!=2 && !deptIds.isEmpty()) { throw invalid(); }
        for(long deptId:deptIds) { if(!found(departments.find(deptId)).getEnabled()) { throw invalid(); } }
        // 共享角色没有固定用户部门；只允许委派自己拥有全部部门范围的权限，工厂范围独立约束。
        for(long menuId:menuIds) {
            var menu=found(menus.find(menuId));
            if(!menu.getEnabled()) { throw invalid(); }
            if(menu.getPermission()!=null) {
                var own=actor.grants().get(menu.getPermission());
                if(own==null || !own.departments().all() || (command.factoryScope()==2 && !own.allFactories())) { throw new BusinessException(GRANT_EXCEEDED); }
            }
        }
        var previous=before==null?Map.<String,Object>of():roleSnapshot(before);
        RoleEntity entity=convert.fromRole(command);
        entity.setId(before==null?null:before.getId()); entity.setVersion(before==null?1L:before.getVersion());
        persist(()->before==null?roles.insert(entity):roles.update(entity));
        security.clearMenus(entity.getId()); security.clearDepartments(entity.getId());
        menuIds.forEach(menuId->security.addMenu(entity.getId(),menuId));
        deptIds.forEach(deptId->security.addDepartment(entity.getId(),deptId));
        var affected=new HashSet<>(security.roleUsers(entity.getId()));
        var result=finish(actor,beforeUsers,affected,"system:role:update",entity.getId());
        AuditContext.record(result.id(),previous,roleSnapshot(found(roles.find(entity.getId()))));
        return result;
    }

    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="部门管理",action="system:dept:update",businessType=UPDATE,fields={"name","parentId","enabled"})
    public ConfigurationChangeVO saveDepartment(String targetId, DepartmentSaveDTO command) {
        if(targetId==null) { throw invalid(); }
        return applyDepartment(targetId,command);
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="系统配置",action="system:dept:update",businessType=com.adtek.common.annotation.OperLog.BusinessType.INSERT,
        fields={"name","parentId","enabled"})
    public ConfigurationChangeVO createDepartment(DepartmentSaveDTO command) { return applyDepartment(null,command); }
    private ConfigurationChangeVO applyDepartment(String targetId, DepartmentSaveDTO command) {
        validator.requireValid(command); Actor actor=policy.lock("system:dept:update");
        var beforeUsers=snapshotUsers();
        DepartmentEntity before=targetId==null?null:found(departments.find(id(targetId)));
        version(before==null?null:before.getVersion(),command.version());
        var tree=bounded(departments.list()); if(before==null) { capacity(tree); }
        Long parent=command.parentId()==null?null:id(command.parentId());
        validateDepartmentParent(before==null?null:before.getId(),parent,tree);
        if(!command.enabled() && before!=null && (usersInDepartment(before.getId()) || tree.stream().anyMatch(d->Objects.equals(d.getParentId(),before.getId()) && d.getEnabled()))) {
            throw new BusinessException(CONFIG_REFERENCE);
        }
        var entity=convert.fromDepartment(command); entity.setId(before==null?null:before.getId()); entity.setVersion(before==null?1L:before.getVersion());
        persist(()->before==null?departments.insert(entity):departments.update(entity));
        var result=finish(actor,beforeUsers,Set.of(),"system:dept:update",entity.getId());
        AuditContext.record(result.id(),before==null?Map.of():departmentSnapshot(before),departmentSnapshot(found(departments.find(entity.getId()))));
        return result;
    }

    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="菜单管理",action="system:menu:update",businessType=UPDATE,fields={"name","parentId","menuType","permission","path","component","sortOrder","enabled"})
    public ConfigurationChangeVO saveManagedMenu(String targetId, ManagedMenuSaveDTO command) {
        if(targetId==null) { throw invalid(); }
        return applyManagedMenu(targetId,command);
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="系统配置",action="system:menu:update",businessType=com.adtek.common.annotation.OperLog.BusinessType.INSERT,
        fields={"name","parentId","menuType","permission","path","component","sortOrder","enabled"})
    public ConfigurationChangeVO createManagedMenu(ManagedMenuSaveDTO command) { return applyManagedMenu(null,command); }
    private ConfigurationChangeVO applyManagedMenu(String targetId, ManagedMenuSaveDTO command) {
        validator.requireValid(command); Actor actor=policy.lock("system:menu:update");
        var beforeUsers=snapshotUsers();
        ManagedMenuEntity before=targetId==null?null:found(menus.find(id(targetId)));
        version(before==null?null:before.getVersion(),command.version());
        var tree=bounded(menus.list()); if(before==null) { capacity(tree); }
        validateMenu(command,before,tree,actor);
        var entity=convert.fromManagedMenu(command); entity.setId(before==null?null:before.getId()); entity.setVersion(before==null?1L:before.getVersion());
        persist(()->before==null?menus.insert(entity):menus.update(entity));
        var result=finish(actor,beforeUsers,new HashSet<>(security.menuUsers(entity.getId())),"system:menu:update",entity.getId());
        AuditContext.record(result.id(),before==null?Map.of():menuSnapshot(before),menuSnapshot(found(menus.find(entity.getId()))));
        return result;
    }

    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="字典类型管理",action="system:dict:update",businessType=UPDATE,fields={"code","name","enabled"})
    public ConfigurationChangeVO saveDictionaryType(String targetId, DictionaryTypeSaveDTO command) {
        if(targetId==null) { throw invalid(); }
        return applyDictionaryType(targetId,command);
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="系统配置",action="system:dict:update",businessType=com.adtek.common.annotation.OperLog.BusinessType.INSERT,
        fields={"code","name","enabled"})
    public ConfigurationChangeVO createDictionaryType(DictionaryTypeSaveDTO command) { return applyDictionaryType(null,command); }
    private ConfigurationChangeVO applyDictionaryType(String targetId, DictionaryTypeSaveDTO command) {
        validator.requireValid(command); policy.lock("system:dict:update");
        DictionaryTypeEntity before=targetId==null?null:found(types.find(id(targetId)));
        version(before==null?null:before.getVersion(),command.version());
        if(before==null) { capacity(types.list()); }
        else if(!before.getCode().equals(command.code())) { throw invalid(); }
        var entity=convert.fromDictionaryType(command); entity.setId(before==null?null:before.getId()); entity.setVersion(before==null?1L:before.getVersion());
        persist(()->before==null?types.insert(entity):types.update(entity));
        AuditContext.record(entity.getId().toString(),before==null?Map.of():typeSnapshot(before),typeSnapshot(found(types.find(entity.getId()))));
        return new ConfigurationChangeVO(entity.getId().toString(),false);
    }

    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="字典值管理",action="system:dict:update",businessType=UPDATE,fields={"typeId","value","labelZh","labelEn","labelVi","sortOrder","enabled"})
    public ConfigurationChangeVO saveDictionaryItem(String targetId, DictionaryItemSaveDTO command) {
        if(targetId==null) { throw invalid(); }
        return applyDictionaryItem(targetId,command);
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    @OperLog(title="系统配置",action="system:dict:update",businessType=com.adtek.common.annotation.OperLog.BusinessType.INSERT,
        fields={"typeId","value","labelZh","labelEn","labelVi","sortOrder","enabled"})
    public ConfigurationChangeVO createDictionaryItem(DictionaryItemSaveDTO command) { return applyDictionaryItem(null,command); }
    private ConfigurationChangeVO applyDictionaryItem(String targetId, DictionaryItemSaveDTO command) {
        validator.requireValid(command); policy.lock("system:dict:update");
        var type=found(types.find(id(command.typeId())));
        if(!type.getEnabled()) { throw new BusinessException(CONFIG_REFERENCE); }
        DictionaryItemEntity before=targetId==null?null:found(items.find(id(targetId)));
        version(before==null?null:before.getVersion(),command.version());
        if(before==null) { capacity(items.list(type.getId())); }
        else if(!before.getValue().equals(command.value()) || !before.getTypeId().equals(type.getId())) { throw invalid(); }
        var entity=convert.fromDictionaryItem(command); entity.setId(before==null?null:before.getId()); entity.setVersion(before==null?1L:before.getVersion());
        persist(()->before==null?items.insert(entity):items.update(entity));
        AuditContext.record(entity.getId().toString(),before==null?Map.of():itemSnapshot(before),itemSnapshot(found(items.find(entity.getId()))));
        return new ConfigurationChangeVO(entity.getId().toString(),false);
    }

    /** 同一协调事务内锁定账号，避免角色版本撤销与并发改密相互覆盖。当前规模采用逐账号授权重算。 */
    private Map<Long,UserState> snapshotUsers() {
        Map<Long,UserState> result=new LinkedHashMap<>();
        for(var user:security.lockUsers()) { result.put(user.getId(),new UserState(user.getDeptId(),authorization.describe(user.getId()))); }
        return result;
    }
    private ConfigurationChangeVO finish(Actor actor,Map<Long,UserState> before,Set<Long> forced,String permission,long objectId) {
        Set<Long> affected=new HashSet<>(forced);
        for(var entry:before.entrySet()) {
            Actor next=authorization.describe(entry.getKey());
            if(!entry.getValue().actor().grants().equals(next.grants())) { affected.add(entry.getKey()); }
            if(affected.contains(entry.getKey())) {
                dominates(actor,entry.getValue().actor(),entry.getValue().department());
                dominates(actor,next,entry.getValue().department());
            }
        }
        if(users.enabledAdministrators()==0) { throw new BusinessException(LAST_ADMIN); }
        Actor nextActor=authorization.describe(actor.userId());
        if(!nextActor.permissions().containsAll(Set.of(ADMIN,permission))
            || !nextActor.grants().get(ADMIN).departments().all()
            || !nextActor.grants().get(permission).departments().all()) { throw new BusinessException(SELF_PROTECTION); }
        for(long userId:affected) { if(security.invalidate(userId,actor.userId())!=1) { throw new BusinessException(CONFIG_CONFLICT); } }
        return new ConfigurationChangeVO(Long.toString(objectId),affected.contains(actor.userId()));
    }
    private record UserState(Long department,Actor actor) {}
    private boolean usersInDepartment(long id) { return security.enabledDepartmentUsers(id)>0; }

    private void validateDepartmentParent(Long target,Long parent,List<DepartmentEntity> tree) {
        Map<Long,DepartmentEntity> index=new HashMap<>(); tree.forEach(d->index.put(d.getId(),d));
        Set<Long> seen=new HashSet<>();
        if(target!=null) { seen.add(target); }
        while(parent!=null) {
            if(!seen.add(parent) || seen.size()>100) { throw new BusinessException(CONFIG_REFERENCE); }
            var ancestor=index.get(parent);
            if(ancestor==null || !ancestor.getEnabled()) { throw new BusinessException(CONFIG_REFERENCE); }
            parent=ancestor.getParentId();
        }
    }
    private void validateMenu(ManagedMenuSaveDTO command,ManagedMenuEntity before,List<ManagedMenuEntity> tree,Actor actor) {
        if(before!=null && (!Objects.equals(before.getPermission(),command.permission()) || !before.getMenuType().equals(command.menuType()))) { throw invalid(); }
        if(command.menuType().equals("M")) {
            if(command.permission()!=null || command.component()!=null || command.path()!=null) { throw invalid(); }
        } else {
            if(command.permission()==null || !actor.permissions().contains(command.permission())) { throw new BusinessException(GRANT_EXCEEDED); }
            if(command.menuType().equals("C")) {
                if(command.component()==null || !Objects.equals(COMPONENTS.get(command.component()),command.path())
                    || !Objects.equals(componentPermission(command.component()),command.permission())) { throw invalid(); }
            } else if(command.component()!=null || command.path()!=null) { throw invalid(); }
        }
        Map<Long,ManagedMenuEntity> index=new HashMap<>(); tree.forEach(m->index.put(m.getId(),m));
        Long parent=command.parentId()==null?null:id(command.parentId());
        if(command.menuType().equals("F") && parent==null) { throw invalid(); }
        if(parent!=null) {
            var direct=index.get(parent);
            if(direct==null || direct.getMenuType().equals("F") || (!command.menuType().equals("F") && !direct.getMenuType().equals("M"))) { throw new BusinessException(CONFIG_REFERENCE); }
        }
        Set<Long> seen=new HashSet<>(); if(before!=null) { seen.add(before.getId()); }
        while(parent!=null) {
            if(!seen.add(parent) || seen.size()>100) { throw new BusinessException(CONFIG_REFERENCE); }
            var ancestor=index.get(parent);
            if(ancestor==null || !ancestor.getEnabled()) { throw new BusinessException(CONFIG_REFERENCE); }
            parent=ancestor.getParentId();
        }
        if(before!=null && !command.enabled() && tree.stream().anyMatch(m->Objects.equals(m.getParentId(),before.getId()) && m.getEnabled())) { throw new BusinessException(CONFIG_REFERENCE); }
    }
    private String componentPermission(String component) {
        return switch(component) {
            case "system.users" -> "system:user:list"; case "system.roles" -> "system:role:list";
            case "system.departments" -> "system:dept:list"; case "system.menus" -> "system:menu:list";
            case "system.dictionaries" -> "system:dict:list"; case "system.audit" -> "system:audit:list"; default -> null;
        };
    }
    private <T> T found(T value) { if(value==null) { throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND); } return value; }
    private <T> List<T> bounded(List<T> values) { if(values.size()>1000) { throw new BusinessException(CONFIG_LIMIT); } return values; }
    private void capacity(List<?> values) { if(values.size()>=1000) { throw new BusinessException(CONFIG_LIMIT); } }
    private void version(Long previous,String submitted) {
        if(previous==null) { if(submitted!=null) { throw invalid(); } }
        else if(submitted==null || previous!=id(submitted)) { throw new BusinessException(CONFIG_CONFLICT); }
    }
    private void persist(IntSupplier write) {
        try { if(write.getAsInt()!=1) { throw new BusinessException(CONFIG_CONFLICT); } }
        catch(DuplicateKeyException error) { throw new BusinessException(CONFIG_DUPLICATE); }
    }
    private List<Long> ids(List<String> values) { return values.stream().map(ConfigurationPolicy::id).distinct().sorted().toList(); }
    private List<String> text(List<Long> values) { return values.stream().map(Object::toString).toList(); }
    private Map<String,Object> roleSnapshot(RoleEntity value) {
        return Map.of("code",value.getCode(),"name",value.getName(),"enabled",value.getEnabled(),"dataScope",value.getDataScope().toString(),
            "factoryScope",value.getFactoryScope().toString(),"menuIds",text(security.roleMenus(value.getId())),"deptIds",text(security.roleDepartments(value.getId())));
    }
    private Map<String,Object> departmentSnapshot(DepartmentEntity value) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("name",value.getName()); result.put("enabled",value.getEnabled());
        result.put("parentId",value.getParentId()==null?null:value.getParentId().toString()); return result;
    }
    private Map<String,Object> menuSnapshot(ManagedMenuEntity value) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("name",value.getName()); result.put("enabled",value.getEnabled()); result.put("parentId",value.getParentId()==null?null:value.getParentId().toString());
        result.put("menuType",value.getMenuType()); result.put("permission",value.getPermission()); result.put("path",value.getPath());
        result.put("component",value.getComponent()); result.put("sortOrder",value.getSortOrder().toString()); return result;
    }
    private Map<String,Object> typeSnapshot(DictionaryTypeEntity value) { return Map.of("code",value.getCode(),"name",value.getName(),"enabled",value.getEnabled()); }
    private Map<String,Object> itemSnapshot(DictionaryItemEntity value) {
        return Map.of("typeId",value.getTypeId().toString(),"value",value.getValue(),"labelZh",value.getLabelZh(),"labelEn",value.getLabelEn(),
            "labelVi",value.getLabelVi(),"sortOrder",value.getSortOrder().toString(),"enabled",value.getEnabled());
    }
}
