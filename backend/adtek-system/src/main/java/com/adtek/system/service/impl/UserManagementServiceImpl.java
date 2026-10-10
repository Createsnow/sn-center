package com.adtek.system.service.impl;

import static com.adtek.common.annotation.OperLog.BusinessType.*;
import static com.adtek.system.constant.ErrorCodeConstants.*;

import com.adtek.common.annotation.OperLog;
import com.adtek.common.core.*;
import com.adtek.framework.audit.AuditContext;
import com.adtek.framework.mybatis.ScopeContext;
import com.adtek.framework.security.*;
import com.adtek.system.convert.UserConvert;
import com.adtek.system.domain.SysUserEntity;
import com.adtek.system.dto.*;
import com.adtek.system.mapper.*;
import com.adtek.system.query.UserQuery;
import com.adtek.system.service.*;
import com.adtek.system.service.support.PasswordPolicy;
import com.adtek.system.vo.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
public class UserManagementServiceImpl implements UserManagementService {
    private static final String ADMIN = "system:security:administer";
    private final UserMapper users;
    private final UserSecurityMapper security;
    private final IdentityMapper identities;
    private final AuthorizationPort authorization;
    private final SystemAuthorizationService grants;
    private final CurrentActor actors;
    private final CommandValidator validator;
    private final UserConvert convert;
    private final PasswordEncoder passwords;
    private final FactoryMembershipPort factories;

    public UserManagementServiceImpl(
            UserMapper users,
            UserSecurityMapper security,
            IdentityMapper identities,
            AuthorizationPort authorization,
            SystemAuthorizationService grants,
            CurrentActor actors,
            CommandValidator validator,
            UserConvert convert,
            PasswordEncoder passwords,
            FactoryMembershipPort factories) {
        this.users = users;
        this.security = security;
        this.identities = identities;
        this.authorization = authorization;
        this.grants = grants;
        this.actors = actors;
        this.validator = validator;
        this.convert = convert;
        this.passwords = passwords;
        this.factories = factories;
    }

    @Override
    @Transactional(readOnly = true)
    @com.adtek.common.annotation.DataScope(
            resource = "system:user",
            permission = "system:user:list")
    public PageResult<ManagedUserVO> list(UserQuery query) {
        validator.requireValid(query);
        var where =
                Wrappers.<SysUserEntity>lambdaQuery()
                        .select(
                                SysUserEntity::getId,
                                SysUserEntity::getUsername,
                                SysUserEntity::getDisplayName,
                                SysUserEntity::getDeptId,
                                SysUserEntity::getEnabled,
                                SysUserEntity::getMustChangePassword,
                                SysUserEntity::getSecurityVersion,
                                SysUserEntity::getLastLoginAt,
                                SysUserEntity::getCreateTime,
                                SysUserEntity::getCreateBy)
                        .eq(SysUserEntity::getPrincipalType, "USER")
                        .eq(
                                query.getEnabled() != null,
                                SysUserEntity::getEnabled,
                                query.getEnabled())
                        .orderByAsc(SysUserEntity::getUsername, SysUserEntity::getId);
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            where.and(
                    part ->
                            part.likeRight(SysUserEntity::getUsername, query.getKeyword().strip())
                                    .or()
                                    .like(
                                            SysUserEntity::getDisplayName,
                                            query.getKeyword().strip()));
        }
        // 所有筛选先进入同一条受数据范围保护的 SQL，再计算总数和分页，禁止页后过滤。
        if (query.getRoleCodes() != null && !query.getRoleCodes().isEmpty()) {
            var members = security.usersWithRoles(query.getRoleCodes());
            if (members.isEmpty())
                return new PageResult<>(List.of(), "0", query.getPage(), query.getSize());
            where.in(SysUserEntity::getId, members);
        }
        if (query.getExcludedRoleCodes() != null && !query.getExcludedRoleCodes().isEmpty()) {
            var excluded = security.usersWithRoles(query.getExcludedRoleCodes());
            if (!excluded.isEmpty()) where.notIn(SysUserEntity::getId, excluded);
        }
        if (query.getFactoryId() != null) {
            var members =
                    factories.members(
                            actors.requirePermission("system:user:list"), id(query.getFactoryId()));
            if (members.isEmpty())
                return new PageResult<>(List.of(), "0", query.getPage(), query.getSize());
            where.in(SysUserEntity::getId, members);
        }
        Page<SysUserEntity> page =
                users.selectPage(new Page<>(query.getPage(), query.getSize()), where);
        return new PageResult<>(
                page.getRecords().stream().map(this::view).toList(),
                Long.toString(page.getTotal()),
                query.getPage(),
                query.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    @com.adtek.common.annotation.DataScope(
            resource = "system:user",
            permission = "system:user:list")
    public ManagedUserVO get(String userId) {
        var user = users.selectById(id(userId));
        if (user == null || !"USER".equals(user.getPrincipalType())) {
            throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND);
        }
        return view(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserOptionsVO options(String operation) {
        if (!Set.of("add", "update").contains(operation)) {
            throw invalid();
        }
        Actor actor = actors.requirePermission("system:user:" + operation);
        var scope = actor.grants().get("system:user:" + operation).departments();
        Long ownDepartment = identities.findById(actor.userId()).getDeptId();
        var departments =
                security.departments().stream()
                        .filter(
                                dept ->
                                        scope.all()
                                                || scope.departmentIds().contains(id(dept.id()))
                                                || (operation.equals("update")
                                                        && scope.self()
                                                        && Objects.equals(
                                                                ownDepartment, id(dept.id()))))
                        .toList();
        return new UserOptionsVO(
                departments, actor.permissions().contains(ADMIN) ? security.roles() : List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuVO> menus() {
        Actor actor = actors.requireAuthenticated();
        if (actor.mustChangePassword()) {
            throw new BusinessException(
                    com.adtek.framework.constant.ErrorCodeConstants.PASSWORD_CHANGE_REQUIRED);
        }
        return security.menus(actor.userId()).stream()
                .filter(
                        menu ->
                                menu.component() != null
                                        && com.adtek.system.service.support.ConfigurationPolicy
                                                .COMPONENTS
                                                .containsKey(menu.component())
                                        && Objects.equals(
                                                com.adtek.system.service.support.ConfigurationPolicy
                                                        .COMPONENTS
                                                        .get(menu.component()),
                                                menu.path()))
                .toList();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @com.adtek.common.annotation.DataScope(resource = "system:user", permission = "system:user:add")
    @OperLog(
            title = "用户管理",
            action = "system:user:add",
            businessType = INSERT,
            fields = {
                "username",
                "displayName",
                "deptId",
                "roleIds",
                "factoryIds",
                "enabled",
                "mustChangePassword"
            })
    public ManagedUserVO create(UserCreateDTO command) {
        validator.requireValid(command);
        Actor actor = lockActor();
        var roles = roleIds(command.roleIds());
        if (!roles.isEmpty() && !actor.permissions().contains(ADMIN)) {
            throw exceeded();
        }
        long department = id(command.deptId());
        requireDepartment(actor, 0, department);
        PasswordPolicy.requireStrong(command.password());
        SysUserEntity user = convert.fromCreate(command);
        user.setPasswordHash(passwords.encode(command.password()));
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user.setSecurityVersion(1L);
        user.setCredentialVersion(1L);
        user.setLanguage("zh-CN");
        user.setCreateBy(actor.userId());
        user.setUpdateBy(actor.userId());
        user.setCreateTime(Instant.now());
        user.setUpdateTime(user.getCreateTime());
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(USERNAME_EXISTS);
        }
        replaceRoles(user.getId(), roles);
        if (command.factoryIds() != null) {
            factories.replace(actor, user.getId(), Set.copyOf(roleIds(command.factoryIds())));
        }
        requireDominates(actor, grants.describe(user.getId()), department);
        AuditContext.record(Long.toString(user.getId()), Map.of(), snapshot(user, roles));
        return convert.toManaged(
                user,
                textIds(roles),
                security.departmentName(department),
                factoryIds(user.getId()),
                security.roleCodes(user.getId()),
                factories.details(user.getId()),
                user.getCreateBy() == null ? null : security.creatorName(user.getCreateBy()));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @com.adtek.common.annotation.DataScope(
            resource = "system:user",
            permission = "system:user:update")
    @OperLog(
            title = "用户管理",
            action = "system:user:update",
            businessType = UPDATE,
            fields = {
                "username",
                "displayName",
                "deptId",
                "roleIds",
                "factoryIds",
                "enabled",
                "mustChangePassword"
            })
    public void update(String userId, UserUpdateDTO command) {
        updateAccount(userId, command, true);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @com.adtek.common.annotation.DataScope(
            resource = "system:user",
            permission = "system:user:update")
    @OperLog(
            title = "用户管理",
            action = "system:user:update",
            businessType = UPDATE,
            fields = {
                "username",
                "displayName",
                "deptId",
                "roleIds",
                "factoryIds",
                "enabled",
                "mustChangePassword"
            })
    public void updatePreservingSession(String userId, UserUpdateDTO command) {
        updateAccount(userId, command, false);
    }

    /** 会话策略只由内部用例选择；授权锁、版本冲突和事务审计共用同一写入路径。 */
    private void updateAccount(String userId, UserUpdateDTO command, boolean revokeSessions) {
        validator.requireValid(command);
        Actor actor = lockActor();
        var user = target(actor, id(userId), command.version());
        var beforeRoles = security.roleIds(user.getId());
        var roles = roleIds(command.roleIds());
        long department = id(command.deptId());
        requireDepartment(actor, user.getId(), department);
        if (!roles.equals(beforeRoles) && !actor.permissions().contains(ADMIN)) {
            throw exceeded();
        }
        var before = snapshot(user, beforeRoles);
        SysUserEntity patch = convert.fromUpdate(command);
        var update =
                mutation(user, actor, revokeSessions)
                        .set(SysUserEntity::getDisplayName, patch.getDisplayName())
                        .set(SysUserEntity::getDeptId, patch.getDeptId());
        requireUpdated(users.update(null, update));
        replaceRoles(user.getId(), roles);
        if (command.factoryIds() != null) {
            factories.replace(actor, user.getId(), Set.copyOf(roleIds(command.factoryIds())));
        }
        requireDominates(actor, grants.describe(user.getId()), department);
        requireRemainingAdministrator();
        user.setDisplayName(patch.getDisplayName());
        user.setDeptId(patch.getDeptId());
        AuditContext.record(userId, before, snapshot(user, roles));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @com.adtek.common.annotation.DataScope(
            resource = "system:user",
            permission = "system:user:status")
    @OperLog(
            title = "用户管理",
            action = "system:user:status",
            businessType = UPDATE,
            fields = {"enabled"})
    public void status(String userId, UserStatusDTO command) {
        changeStatus(userId, command, true);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @com.adtek.common.annotation.DataScope(
            resource = "system:user",
            permission = "system:user:status")
    @OperLog(
            title = "用户管理",
            action = "system:user:status",
            businessType = UPDATE,
            fields = {"enabled"})
    public String statusPreservingSession(String userId, UserStatusDTO command) {
        return changeStatus(userId, command, false);
    }

    private String changeStatus(String userId, UserStatusDTO command, boolean revokeSessions) {
        validator.requireValid(command);
        Actor actor = lockActor();
        var user = lockedTarget(actor, id(userId));
        if (user.getId() == actor.userId() && !command.enabled()) {
            throw new BusinessException(SELF_PROTECTION);
        }
        if (!revokeSessions && user.getEnabled().equals(command.enabled())) {
            AuditContext.record(
                    userId,
                    Map.of("enabled", user.getEnabled()),
                    Map.of("enabled", user.getEnabled()));
            return user.getSecurityVersion().toString();
        }
        requireVersion(user, command.version());
        boolean previous = user.getEnabled();
        requireUpdated(
                users.update(
                        null,
                        mutation(user, actor, revokeSessions)
                                .set(SysUserEntity::getEnabled, command.enabled())));
        requireRemainingAdministrator();
        AuditContext.record(
                userId, Map.of("enabled", previous), Map.of("enabled", command.enabled()));
        return Long.toString(Math.addExact(user.getSecurityVersion(), 1L));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @com.adtek.common.annotation.DataScope(
            resource = "system:user",
            permission = "system:user:reset")
    @OperLog(
            title = "用户管理",
            action = "system:user:reset",
            businessType = UPDATE,
            fields = {"mustChangePassword"})
    public void resetPassword(String userId, UserPasswordResetDTO command) {
        resetPassword(userId, command, false);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @com.adtek.common.annotation.DataScope(
            resource = "system:user",
            permission = "system:user:reset")
    @OperLog(
            title = "用户管理",
            action = "system:user:reset",
            businessType = UPDATE,
            fields = {"mustChangePassword"})
    public String resetInitialPassword(String userId, UserPasswordResetDTO command) {
        return resetPassword(userId, command, true);
    }

    private String resetPassword(String userId, UserPasswordResetDTO command, boolean initial) {
        validator.requireValid(command);
        Actor actor = lockActor();
        var user = target(actor, id(userId), command.version());
        if (!initial && user.getId() == actor.userId()) {
            throw new BusinessException(SELF_PROTECTION);
        }
        PasswordPolicy.requireStrong(command.password());
        if (!initial && passwords.matches(command.password(), user.getPasswordHash())) {
            throw new BusinessException(PASSWORD_SAME);
        }
        requireUpdated(
                users.update(
                        null,
                        mutation(user, actor)
                                .set(
                                        SysUserEntity::getPasswordHash,
                                        passwords.encode(command.password()))
                                .set(SysUserEntity::getMustChangePassword, true)));
        AuditContext.record(
                userId,
                Map.of("mustChangePassword", user.getMustChangePassword()),
                Map.of("mustChangePassword", true));
        return Long.toString(Math.addExact(user.getSecurityVersion(), 1L));
    }

    /** 协调锁先于账号锁，所有未来角色/菜单/部门权限写也必须使用相同顺序。 */
    private Actor lockActor() {
        var request = actors.requirePermission(ScopeContext.require().permission());
        if (security.lockControl() == null) {
            throw new IllegalStateException("Missing security coordination row");
        }
        var locked = identities.lockById(request.userId());
        if (locked == null
                || !locked.getEnabled()
                || locked.getSecurityVersion() != request.securityVersion()) {
            throw new BusinessException(ACCOUNT_CHANGED);
        }
        Actor current =
                authorization
                        .loadCurrent(request.userId())
                        .orElseThrow(() -> new BusinessException(ACCOUNT_CHANGED));
        if (current.mustChangePassword()) {
            throw new BusinessException(
                    com.adtek.framework.constant.ErrorCodeConstants.PASSWORD_CHANGE_REQUIRED);
        }
        ScopeContext.refresh(current);
        return current;
    }

    private SysUserEntity target(Actor actor, long targetId, String version) {
        var user = lockedTarget(actor, targetId);
        requireVersion(user, version);
        return user;
    }

    private void requireVersion(SysUserEntity user, String version) {
        if (user.getSecurityVersion() != id(version)) throw new BusinessException(USER_CONFLICT);
    }

    private SysUserEntity lockedTarget(Actor actor, long targetId) {
        var user = users.lock(targetId);
        if (user == null || !"USER".equals(user.getPrincipalType())) {
            throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND);
        }
        requireDominates(actor, grants.describe(targetId), user.getDeptId());
        return user;
    }

    /** 每个权限独立比较；本人范围不能拿来覆盖另一个人的本人范围。工厂资格独立比较。 */
    private void requireDominates(Actor actor, Actor target, Long targetDepartment) {
        if (target.permissions().contains(ADMIN) && !actor.permissions().contains(ADMIN)) {
            throw exceeded();
        }
        for (var entry : target.grants().entrySet()) {
            var own = actor.grants().get(entry.getKey());
            var requested = entry.getValue();
            if (own == null) {
                throw exceeded();
            }
            var allowed = own.departments();
            var actual = requested.departments();
            if (!allowed.all()
                    && (actual.all()
                            || !allowed.departmentIds().containsAll(actual.departmentIds())
                            || (actual.self()
                                    && !allowed.permits(
                                            actor.userId(), target.userId(), targetDepartment)))) {
                throw exceeded();
            }
            if (!own.allFactories()
                    && (requested.allFactories()
                            || !own.factoryIds().containsAll(requested.factoryIds()))) {
                throw exceeded();
            }
        }
    }

    private void requireDepartment(Actor actor, long targetId, long department) {
        var scope = actor.grants().get(ScopeContext.require().permission()).departments();
        if (!scope.permits(actor.userId(), targetId, department)) {
            throw exceeded();
        }
        if (security.enabledDepartment(department) != 1) {
            throw invalid();
        }
    }

    private void requireRemainingAdministrator() {
        if (security.enabledAdministrators() == 0) {
            throw new BusinessException(LAST_ADMIN);
        }
    }

    private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUserEntity>
            mutation(SysUserEntity user, Actor actor) {
        return mutation(user, actor, true);
    }

    private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUserEntity>
            mutation(SysUserEntity user, Actor actor, boolean revokeSessions) {
        return Wrappers.<SysUserEntity>lambdaUpdate()
                .eq(SysUserEntity::getId, user.getId())
                .eq(SysUserEntity::getSecurityVersion, user.getSecurityVersion())
                .set(
                        SysUserEntity::getSecurityVersion,
                        Math.addExact(user.getSecurityVersion(), 1L))
                .set(
                        revokeSessions,
                        SysUserEntity::getCredentialVersion,
                        Math.addExact(user.getCredentialVersion(), 1L))
                .set(SysUserEntity::getUpdateBy, actor.userId())
                .set(SysUserEntity::getUpdateTime, Instant.now());
    }

    private void requireUpdated(int count) {
        if (count != 1) {
            throw new BusinessException(USER_CONFLICT);
        }
    }

    private void replaceRoles(long userId, List<Long> roles) {
        for (long role : roles) {
            if (security.enabledRole(role) != 1) {
                throw invalid();
            }
        }
        security.clearRoles(userId);
        roles.forEach(role -> security.addRole(userId, role));
    }

    private ManagedUserVO view(SysUserEntity user) {
        return convert.toManaged(
                user,
                textIds(security.roleIds(user.getId())),
                user.getDeptId() == null ? null : security.departmentName(user.getDeptId()),
                factoryIds(user.getId()),
                security.roleCodes(user.getId()),
                factories.details(user.getId()),
                user.getCreateBy() == null ? null : security.creatorName(user.getCreateBy()));
    }

    private List<String> factoryIds(long userId) {
        return factories.factoryIds(userId).stream().sorted().map(Object::toString).toList();
    }

    private List<String> textIds(List<Long> ids) {
        return ids.stream().map(Object::toString).toList();
    }

    private List<Long> roleIds(List<String> ids) {
        return ids.stream().map(this::id).distinct().sorted().toList();
    }

    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw invalid();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private Map<String, Object> snapshot(SysUserEntity user, List<Long> roles) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("deptId", user.getDeptId() == null ? null : user.getDeptId().toString());
        result.put("roleIds", textIds(roles));
        result.put("factoryIds", factoryIds(user.getId()));
        result.put("enabled", user.getEnabled());
        result.put("mustChangePassword", user.getMustChangePassword());
        return result;
    }

    private BusinessException invalid() {
        return new BusinessException(com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT);
    }

    private BusinessException exceeded() {
        return new BusinessException(GRANT_EXCEEDED);
    }
}
