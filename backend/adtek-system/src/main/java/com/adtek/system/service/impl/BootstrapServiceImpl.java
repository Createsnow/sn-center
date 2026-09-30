package com.adtek.system.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.CommandValidator;
import com.adtek.system.constant.ErrorCodeConstants;
import com.adtek.system.convert.UserConvert;
import com.adtek.system.dto.BootstrapAdminDTO;
import com.adtek.system.mapper.BootstrapMapper;
import com.adtek.system.service.AuthenticationAuditService;
import com.adtek.system.service.BootstrapService;
import com.adtek.system.service.support.PasswordPolicy;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 初始化入口关闭时不装配；不使用用户 ID 等于 1 作为任何授权条件。 */
@Service
@Profile({"bootstrap-admin", "integration-test"})
public class BootstrapServiceImpl implements BootstrapService {
    private final BootstrapMapper mapper;
    private final UserConvert convert;
    private final PasswordEncoder passwords;
    private final CommandValidator validator;
    private final AuthenticationAuditService audit;
    public BootstrapServiceImpl(BootstrapMapper mapper, UserConvert convert, PasswordEncoder passwords,
            CommandValidator validator, AuthenticationAuditService audit) {
        this.mapper = mapper; this.convert = convert; this.passwords = passwords; this.validator = validator; this.audit = audit;
    }

    @Override
    @Transactional
    public String initialize(BootstrapAdminDTO command) {
        validator.requireValid(command);
        PasswordPolicy.requireStrong(command.password());
        if (!Boolean.FALSE.equals(mapper.lockInitialization()) || mapper.countUsers() != 0) {
            throw new BusinessException(ErrorCodeConstants.ALREADY_INITIALIZED);
        }
        var user = convert.fromBootstrap(command);
        user.setPasswordHash(passwords.encode(command.password()));
        user.setDeptId(1L);
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user.setSecurityVersion(1L);
        user.setLanguage("zh-CN");
        mapper.insert(user);
        mapper.markOwnership(user.getId());
        mapper.assignInitialRoles(user.getId());
        audit.recordSuccess(user.getId(), AuthenticationAuditService.Action.BOOTSTRAP);
        if (mapper.finishInitialization() != 1) { throw new IllegalStateException("Bootstrap control row changed"); }
        return user.getId().toString();
    }
}
