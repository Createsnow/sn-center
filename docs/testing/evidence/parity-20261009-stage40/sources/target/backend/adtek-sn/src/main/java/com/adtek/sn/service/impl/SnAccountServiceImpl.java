package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.AccountAdministrationPort;
import com.adtek.framework.security.CurrentActor;
import com.adtek.sn.constant.ErrorCodeConstants;
import com.adtek.sn.dto.SnAccountCreateDTO;
import com.adtek.sn.dto.SnAccountUpdateDTO;
import com.adtek.sn.mapper.FactoryMapper;
import com.adtek.sn.service.SnAccountService;
import com.adtek.sn.vo.SnAccountSavedVO;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 三角色映射由SN拥有；身份、部门、安全锁与审计通过framework端口交给system。 */
@Service
public class SnAccountServiceImpl implements SnAccountService {
    private final AccountAdministrationPort accounts;
    private final CurrentActor actors;
    private final FactoryMapper factories;

    public SnAccountServiceImpl(
            AccountAdministrationPort accounts, CurrentActor actors, FactoryMapper factories) {
        this.accounts = accounts;
        this.actors = actors;
        this.factories = factories;
    }

    @Override
    public SnAccountSavedVO create(SnAccountCreateDTO command) {
        administrator("add");
        String username = trim(command.username());
        if (!username.matches("[A-Za-z0-9_-]{2,32}")) throw invalid("USER_EMP_NO_INVALID");
        String name = name(command.displayName());
        String role = trim(command.role());
        String factory = factory(role, command.factoryId());
        try {
            return new SnAccountSavedVO(
                    accounts.create(
                            new AccountAdministrationPort.Create(
                                    username,
                                    name,
                                    roles(role, factory),
                                    factory == null ? List.of() : List.of(factory),
                                    command.password())));
        } catch (AccountAdministrationPort.Rejected error) {
            if (error.reason() == AccountAdministrationPort.Reason.DUPLICATE_USERNAME)
                throw new BusinessException(
                        ErrorCodeConstants.ACCOUNT_EXISTS,
                        Map.of("reason", "USER_EMP_NO_EXISTS", "emp_no", username));
            throw invalid("USER_PASSWORD_WEAK");
        }
    }

    @Override
    public void update(String id, SnAccountUpdateDTO command) {
        administrator("update");
        var previous = accounts.read(id);
        if (!canonical(previous)) throw new BusinessException(ErrorCodeConstants.ACCOUNT_EXTENDED);
        String name = name(command.displayName());
        String role = trim(command.role());
        String factory = factory(role, command.factoryId());
        accounts.update(
                id,
                new AccountAdministrationPort.Update(
                        name,
                        roles(role, factory),
                        factory == null ? List.of() : List.of(factory),
                        command.version()));
    }

    private void administrator(String operation) {
        actors.requirePermission("system:user:" + operation);
        actors.requirePermission("system:security:administer");
    }

    private boolean canonical(AccountAdministrationPort.Profile profile) {
        var roles = Set.copyOf(profile.roleCodes());
        int count = profile.factoryIds().size();
        return roles.equals(Set.of("PLATFORM_ADMIN", "SN_HQ")) && count == 0
                || roles.equals(Set.of("FACTORY_OPERATOR")) && count == 1
                || roles.equals(Set.of("SN_QUERY_ALL")) && count == 0
                || roles.equals(Set.of("SN_QUERY_FACTORY")) && count == 1;
    }

    private List<String> roles(String role, String factory) {
        return switch (role) {
            case "admin" -> List.of("PLATFORM_ADMIN", "SN_HQ");
            case "factory_operator" -> List.of("FACTORY_OPERATOR");
            default -> List.of(factory == null ? "SN_QUERY_ALL" : "SN_QUERY_FACTORY");
        };
    }

    private String factory(String role, String value) {
        if (!Set.of("admin", "factory_operator", "query").contains(role))
            throw invalid("USER_ROLE_INVALID");
        String id = trim(value);
        if (id.isEmpty()) id = null;
        if (role.equals("admin") && id != null) throw invalid("USER_ADMIN_NO_FACTORY");
        if (role.equals("factory_operator") && id == null) throw invalid("USER_FACTORY_REQUIRED");
        if (id != null) {
            long numeric;
            try {
                numeric = Long.parseLong(id);
            } catch (NumberFormatException error) {
                throw invalid("FACTORY_NOT_FOUND");
            }
            if (numeric <= 0 || factories.selectById(numeric) == null)
                throw invalid("FACTORY_NOT_FOUND");
        }
        return id;
    }

    private String name(String value) {
        String name = trim(value);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 64)
            throw invalid("USER_NAME_REQUIRED");
        return name;
    }

    private String trim(String value) {
        return value == null ? "" : value.strip();
    }

    private BusinessException invalid(String reason) {
        return new BusinessException(ErrorCodeConstants.ACCOUNT_INVALID, Map.of("reason", reason));
    }
}
