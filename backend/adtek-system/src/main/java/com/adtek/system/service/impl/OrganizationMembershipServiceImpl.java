package com.adtek.system.service.impl;

import com.adtek.framework.security.OrganizationMembershipPort;
import com.adtek.system.mapper.IdentityMapper;
import com.adtek.system.mapper.UserSecurityMapper;

import org.springframework.stereotype.Service;

import java.util.Optional;

/** 当前所属部门由身份模块维护；业务不能从请求正文指定所属部门。 */
@Service
public class OrganizationMembershipServiceImpl implements OrganizationMembershipPort {
    private final IdentityMapper users;
    private final UserSecurityMapper security;

    public OrganizationMembershipServiceImpl(IdentityMapper users, UserSecurityMapper security) {
        this.users = users;
        this.security = security;
    }

    public Optional<Long> currentDepartment(long userId) {
        var user = users.findById(userId);
        return user == null
                        || !Boolean.TRUE.equals(user.getEnabled())
                        || user.getDeptId() == null
                        || security.enabledDepartment(user.getDeptId()) != 1
                ? Optional.empty()
                : Optional.of(user.getDeptId());
    }
}
