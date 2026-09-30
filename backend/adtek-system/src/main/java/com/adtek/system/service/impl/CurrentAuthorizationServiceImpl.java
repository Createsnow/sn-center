package com.adtek.system.service.impl;

import com.adtek.common.core.Actor;
import com.adtek.common.core.AuthorizationScope;
import com.adtek.common.core.DataScope;
import com.adtek.common.core.DataScopeType;
import com.adtek.framework.security.AuthorizationPort;
import com.adtek.framework.security.FactoryMembershipPort;
import com.adtek.system.mapper.IdentityMapper;
import com.adtek.system.service.SystemAuthorizationService;
import com.adtek.system.domain.SysUserEntity;
import com.adtek.common.core.BusinessException;
import com.adtek.common.constant.ErrorCodeConstants;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单次事务一致快照读取当前身份；范围只在授予相同 permissionCode 的角色之间合并。 */
@Service
public class CurrentAuthorizationServiceImpl implements AuthorizationPort, SystemAuthorizationService {
    private final IdentityMapper identities;
    private final FactoryMembershipPort factories;
    private final com.adtek.system.mapper.ServiceClientMapper clients;
    public CurrentAuthorizationServiceImpl(IdentityMapper identities, FactoryMembershipPort factories, com.adtek.system.mapper.ServiceClientMapper clients) { this.identities = identities; this.factories=factories; this.clients=clients; }

    @Override
    @Transactional(readOnly = true)
    public Optional<Actor> loadCurrent(long userId) {
        var user = identities.findById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) { return Optional.empty(); }
        return Optional.of(resolve(user));
    }

    @Override @Transactional(readOnly = true)
    public Actor describe(long userId) {
        var user = identities.findById(userId);
        if (user == null) { throw new BusinessException(ErrorCodeConstants.NOT_FOUND); }
        return resolve(user);
    }

    private Actor resolve(SysUserEntity user) {
        long userId = user.getId();
        if ("SERVICE".equals(user.getPrincipalType())) {
            Map<String,AuthorizationScope> serviceGrants=new HashMap<>();
            var scope=new AuthorizationScope(new DataScope(false,Set.of(),true),false,factories.factoryIds(userId));
            clients.permissions(userId).forEach(permission->serviceGrants.put(permission,scope));
            return new Actor(userId,user.getSecurityVersion(),false,serviceGrants,"SERVICE");
        }
        var rows = identities.findRolePermissions(userId);
        Set<Long> factoryIds = factories.factoryIds(userId);
        Map<Long, Set<Long>> custom = new HashMap<>();
        Map<String, AuthorizationScope> grants = new HashMap<>();
        Set<Long> tree = null;
        for (var row : rows) {
            DataScope departments = switch (DataScopeType.fromCode(row.dataScope())) {
                case ALL -> new DataScope(true, Set.of(), false);
                case CUSTOM_DEPARTMENTS -> new DataScope(false, custom.computeIfAbsent(row.roleId(),
                        id -> Set.copyOf(identities.findCustomDepartments(id))), false);
                case DEPARTMENT -> new DataScope(false, user.getDeptId() == null ? Set.of() : Set.of(user.getDeptId()), false);
                case DEPARTMENT_AND_DESCENDANTS -> {
                    if (tree == null) { tree = user.getDeptId() == null ? Set.of() : Set.copyOf(identities.findDepartmentTree(user.getDeptId())); }
                    yield new DataScope(false, tree, false);
                }
                case SELF -> new DataScope(false, Set.of(), true);
            };
            var current = new AuthorizationScope(departments, row.allFactories(), factoryIds);
            grants.merge(row.permissionCode(), current, CurrentAuthorizationServiceImpl::union);
        }
        return new Actor(userId, user.getSecurityVersion(), user.getMustChangePassword(), grants);
    }

    private static AuthorizationScope union(AuthorizationScope first, AuthorizationScope second) {
        Set<Long> departments = new HashSet<>(first.departments().departmentIds());
        departments.addAll(second.departments().departmentIds());
        Set<Long> factories = new HashSet<>(first.factoryIds());
        factories.addAll(second.factoryIds());
        return new AuthorizationScope(new DataScope(
                first.departments().all() || second.departments().all(), departments,
                first.departments().self() || second.departments().self()),
                first.allFactories() || second.allFactories(), factories);
    }
}
