package com.adtek.sn.service.impl;

import com.adtek.framework.security.PrincipalPort;
import com.adtek.sn.domain.UserEntity;
import com.adtek.sn.support.Passwords;
import com.adtek.sn.support.Util;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 当前授权（framework {@link PrincipalPort} 的提供方）：按账户主键读取 sn_user 与授权生产组织，不缓存；口令版本 = 改密时间秒数。
 *
 * <p>MES 接口账户只能调 MES 机器接口（interfaceOnly）；普通操作员、审计员没有授权组织时 scopeMissing。
 */
@Service
public class PrincipalServiceImpl implements PrincipalPort {
    private final UserServiceImpl users;

    public PrincipalServiceImpl(UserServiceImpl users) {
        this.users = users;
    }

    @Override
    public Optional<Snapshot> load(long userId) {
        UserEntity u = users.byId(userId);
        if (u == null) {
            return Optional.empty();
        }
        List<String> scope = users.readScope(u.id, u.role, u.factoryCode);
        return Optional.of(
                new Snapshot(
                        u.id,
                        u.empNo,
                        u.name,
                        u.role,
                        u.factoryCode,
                        scope,
                        Passwords.version(u.pwdChangedAt),
                        UserServiceImpl.ACTIVE.equals(u.status),
                        Util.MES.equals(u.role),
                        Util.SCOPED_ROLES.contains(u.role) && scope.isEmpty()));
    }
}
