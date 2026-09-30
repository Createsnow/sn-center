package com.adtek.system.service.impl;

import com.adtek.common.core.*;
import com.adtek.framework.security.*;
import com.adtek.system.mapper.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 与用户/配置写共用锁顺序，避免SN写在授权撤销之后仍按旧资格提交。 */
@Service
public class AuthorizationMutationServiceImpl implements AuthorizationMutationPort {
    private final UserSecurityMapper security;
    private final IdentityMapper identities;
    private final AuthorizationPort authorization;

    public AuthorizationMutationServiceImpl(
            UserSecurityMapper security,
            IdentityMapper identities,
            AuthorizationPort authorization) {
        this.security = security;
        this.identities = identities;
        this.authorization = authorization;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Actor lockCurrent(Actor requested) {
        if (security.lockControl() == null) {
            throw new IllegalStateException("Missing security control");
        }
        var user = identities.lockById(requested.userId());
        if (user == null
                || !user.getEnabled()
                || user.getSecurityVersion() != requested.securityVersion()) {
            throw new BusinessException(
                    com.adtek.system.constant.ErrorCodeConstants.ACCOUNT_CHANGED);
        }
        var current =
                authorization
                        .loadCurrent(requested.userId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                com.adtek.framework.constant.ErrorCodeConstants
                                                        .SESSION_INVALID));
        if (current.mustChangePassword()) {
            throw new BusinessException(
                    com.adtek.framework.constant.ErrorCodeConstants.PASSWORD_CHANGE_REQUIRED);
        }
        return current;
    }
}
