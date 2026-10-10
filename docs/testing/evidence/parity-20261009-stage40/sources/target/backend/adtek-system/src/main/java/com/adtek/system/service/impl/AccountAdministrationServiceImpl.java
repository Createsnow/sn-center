package com.adtek.system.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.AccountAdministrationPort;
import com.adtek.framework.security.CurrentActor;
import com.adtek.system.constant.ErrorCodeConstants;
import com.adtek.system.convert.UserConvert;
import com.adtek.system.dto.UserCreateDTO;
import com.adtek.system.dto.UserUpdateDTO;
import com.adtek.system.mapper.IdentityMapper;
import com.adtek.system.mapper.UserSecurityMapper;
import com.adtek.system.service.UserManagementService;
import com.adtek.system.service.support.PasswordPolicy;

import org.springframework.stereotype.Service;

import java.util.List;

/** 业务账号适配：不复制安全写事务；每次调用代理后的管理用例复核授权、版本、角色和审计。 */
@Service
public class AccountAdministrationServiceImpl implements AccountAdministrationPort {
    private final UserManagementService users;
    private final UserSecurityMapper security;
    private final IdentityMapper identities;
    private final CurrentActor actors;
    private final UserConvert convert;

    public AccountAdministrationServiceImpl(
            UserManagementService users,
            UserSecurityMapper security,
            IdentityMapper identities,
            CurrentActor actors,
            UserConvert convert) {
        this.users = users;
        this.security = security;
        this.identities = identities;
        this.actors = actors;
        this.convert = convert;
    }

    @Override
    public Profile read(String id) {
        return convert.accountProfile(users.get(id));
    }

    @Override
    public String create(Create command) {
        var actor = actors.requirePermission("system:user:add");
        var department = identities.findById(actor.userId()).getDeptId();
        String selected = department("add", department == null ? null : department.toString());
        try {
            PasswordPolicy.requireStrong(command.password());
            return users.create(
                            new UserCreateDTO(
                                    command.username(),
                                    command.displayName(),
                                    selected,
                                    roles(command.roleCodes()),
                                    command.factoryIds(),
                                    command.password()))
                    .id();
        } catch (BusinessException error) {
            if (error.getError().equals(ErrorCodeConstants.USERNAME_EXISTS))
                throw new Rejected(Reason.DUPLICATE_USERNAME);
            if (error.getError().equals(ErrorCodeConstants.PASSWORD_WEAK))
                throw new Rejected(Reason.PASSWORD_WEAK);
            throw error;
        }
    }

    @Override
    public void update(String id, Update command) {
        actors.requirePermission("system:user:update");
        var user = users.get(id);
        users.update(
                id,
                new UserUpdateDTO(
                        command.displayName(),
                        user.deptId() == null ? department("update", null) : user.deptId(),
                        roles(command.roleCodes()),
                        command.factoryIds(),
                        command.version()));
    }

    private List<String> roles(List<String> codes) {
        return codes.stream()
                .map(
                        code -> {
                            var id = security.enabledRoleId(code);
                            if (id == null)
                                throw new BusinessException(ErrorCodeConstants.GRANT_EXCEEDED);
                            return id.toString();
                        })
                .toList();
    }

    /** SN页面不暴露部门；新建优先使用操作人部门，缺失时取当前权限内的第一个启用部门。 */
    private String department(String operation, String preferred) {
        var options = users.options(operation).departments();
        if (preferred != null && options.stream().anyMatch(d -> preferred.equals(d.id())))
            return preferred;
        return options.stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCodeConstants.GRANT_EXCEEDED))
                .id();
    }
}
