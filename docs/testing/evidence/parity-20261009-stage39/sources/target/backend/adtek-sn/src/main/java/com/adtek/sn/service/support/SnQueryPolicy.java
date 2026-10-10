package com.adtek.sn.service.support;

import com.adtek.common.core.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.query.SnItemQuery;

import org.springframework.stereotype.Component;

/** 列表和导出按各自动作范围求交；同PI例外对双方权限都验证当前本厂归属。 */
@Component
public class SnQueryPolicy {
    public static final String VIEW = "sn:query:view",
            EXPORT = "sn:query:export",
            DASHBOARD = "sn:dashboard:view";
    private final AcquirePolicy policy;
    private final AcquireMapper acquire;

    public SnQueryPolicy(AcquirePolicy policy, AcquireMapper acquire) {
        this.policy = policy;
        this.acquire = acquire;
    }

    public Actor read(String permission) {
        return require(policy.read(permission), permission);
    }

    public Actor require(Actor actor, String permission) {
        policy.require(actor, permission, null);
        if (!"USER".equals(actor.principalType())) throw AcquirePolicy.forbidden();
        return actor;
    }

    public SnQueryMapper.Scope scope(Actor actor, String permission, SnItemQuery q) {
        var grant = require(actor, permission).grants().get(permission);
        Long factory = q.factoryId() == null ? null : SnPolicy.id(q.factoryId());
        if (factory != null && !grant.permitsFactory(factory)) throw AcquirePolicy.forbidden();
        if (q.piAll()) {
            if (q.pi() == null) throw AcquirePolicy.forbidden();
            String pi = AcquirePolicy.pi(q.pi());
            if (!grant.allFactories()
                    && acquire.owns(
                                    new AcquireMapper.Scope(
                                            false,
                                            grant.factoryIds().stream().sorted().toList(),
                                            null,
                                            pi,
                                            false))
                            == 0) {
                // 保持已发布的拒绝码，只返回用户输入的 PI，不暴露其他工厂的成员。
                throw new BusinessException(
                        com.adtek.framework.constant.ErrorCodeConstants.FORBIDDEN,
                        java.util.Map.of("reason", "QUERY_PI_NOT_OWNED", "pi", pi));
            }
        }
        return new SnQueryMapper.Scope(
                grant.allFactories(), grant.factoryIds().stream().sorted().toList(), q.piAll());
    }

    public SnQueryMapper.Scope basic(Actor actor, String permission) {
        var grant = require(actor, permission).grants().get(permission);
        return new SnQueryMapper.Scope(
                grant.allFactories(), grant.factoryIds().stream().sorted().toList(), false);
    }

    public SnQueryMapper.Scope intersection(SnQueryMapper.Scope a, SnQueryMapper.Scope b) {
        if (a.piAll() != b.piAll()) throw SnPolicy.invalid();
        if (a.all()) return b;
        if (b.all()) return a;
        var ids = new java.util.TreeSet<>(a.factories());
        ids.retainAll(b.factories());
        return new SnQueryMapper.Scope(false, java.util.List.copyOf(ids), a.piAll());
    }
}
