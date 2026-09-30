package com.adtek.sn.service.support;

import com.adtek.common.core.Actor;
import com.adtek.sn.domain.TransferEntity;
import com.adtek.sn.mapper.TransferMapper;

import org.springframework.stereotype.Component;

/** 交接目录权限与库存读取分开；批准/直转需该动作全厂，撤回只需原来源厂。 */
@Component
public class TransferPolicy {
    public static final String VIEW = "sn:transfer:view", TARGETS = "sn:transfer:targets";
    private final AcquirePolicy policy;

    public TransferPolicy(AcquirePolicy policy) {
        this.policy = policy;
    }

    public static String permission(String kind) {
        if (!java.util.Set.of("APPLY", "DIRECT", "APPROVE", "REJECT", "WITHDRAW").contains(kind))
            throw SnPolicy.invalid();
        return "sn:transfer:" + kind.toLowerCase(java.util.Locale.ROOT);
    }

    public Actor read(String permission) {
        return require(policy.read(permission), permission, null);
    }

    public Actor require(Actor actor, String permission, Long factory) {
        policy.require(actor, permission, factory);
        if (!"USER".equals(actor.principalType())
                || ((permission.endsWith(":direct")
                                || permission.endsWith(":approve")
                                || permission.endsWith(":reject"))
                        && !actor.grants().get(permission).allFactories()))
            throw AcquirePolicy.forbidden();
        return actor;
    }

    public Actor lock(Actor actor, String permission, long factory) {
        return require(policy.lock(actor, permission, factory), permission, factory);
    }

    public void visible(Actor actor, TransferEntity row) {
        var grant = actor.grants().get(VIEW);
        if (grant == null
                || (!grant.permitsFactory(row.fromFactoryId())
                        && !grant.permitsFactory(row.toFactoryId())))
            throw AcquirePolicy.forbidden();
    }

    public TransferMapper.Scope scope(Actor actor, Long factory) {
        var grant = actor.grants().get(VIEW);
        if (factory != null && !grant.permitsFactory(factory)) throw AcquirePolicy.forbidden();
        return new TransferMapper.Scope(
                grant.allFactories(), grant.factoryIds().stream().sorted().toList(), factory);
    }

    public String direction(Actor actor, TransferEntity row, Long factory) {
        var grant = actor.grants().get(VIEW);
        boolean from =
                factory == null
                        ? grant.permitsFactory(row.fromFactoryId())
                        : factory == row.fromFactoryId();
        boolean to =
                factory == null
                        ? grant.permitsFactory(row.toFactoryId())
                        : factory == row.toFactoryId();
        return from && to ? "BOTH" : from ? "OUT" : "IN";
    }
}
