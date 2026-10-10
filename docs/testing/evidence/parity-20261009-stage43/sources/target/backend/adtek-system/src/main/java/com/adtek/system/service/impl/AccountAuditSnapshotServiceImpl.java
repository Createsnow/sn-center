package com.adtek.system.service.impl;

import com.adtek.framework.audit.AccountAuditSnapshotPort;
import com.adtek.framework.security.FactoryMembershipPort;
import com.adtek.system.mapper.OperLogMapper;
import com.adtek.system.mapper.UserSecurityMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 只读身份及授权关系；成功审计加入原事务，失败审计在自己的事务内读取已提交状态。 */
@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
public class AccountAuditSnapshotServiceImpl implements AccountAuditSnapshotPort {
    private final OperLogMapper logs;
    private final UserSecurityMapper roles;
    private final FactoryMembershipPort factories;

    public AccountAuditSnapshotServiceImpl(
            OperLogMapper logs, UserSecurityMapper roles, FactoryMembershipPort factories) {
        this.logs = logs;
        this.roles = roles;
        this.factories = factories;
    }

    @Override
    public Optional<Snapshot> read(long userId) {
        var actor = logs.actorSnapshot(userId);
        return actor == null
                ? Optional.empty()
                : Optional.of(
                        new Snapshot(
                                actor.username(),
                                actor.displayName(),
                                actor.principalType(),
                                roles.roleCodes(userId),
                                factories.factoryIds(userId).stream()
                                        .sorted()
                                        .map(Object::toString)
                                        .toList()));
    }

    @Override
    public List<String> roleCodes(List<String> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        if (roleIds.stream().anyMatch(id -> !id.matches("[1-9][0-9]{0,18}")))
            throw new IllegalArgumentException("Invalid trusted audit role identity");
        return roles.auditRoleCodes(roleIds);
    }
}
