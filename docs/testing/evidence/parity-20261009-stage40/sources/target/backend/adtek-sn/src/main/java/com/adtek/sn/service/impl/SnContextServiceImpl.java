package com.adtek.sn.service.impl;

import com.adtek.sn.convert.SnConvert;
import com.adtek.sn.mapper.FactoryMapper;
import com.adtek.sn.service.SnContextService;
import com.adtek.sn.service.support.AcquirePolicy;
import com.adtek.sn.service.support.SnQueryPolicy;
import com.adtek.sn.vo.SnContextVO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class SnContextServiceImpl implements SnContextService {
    private final SnQueryPolicy policy;
    private final FactoryMapper factories;
    private final SnConvert convert;

    public SnContextServiceImpl(SnQueryPolicy policy, FactoryMapper factories, SnConvert convert) {
        this.policy = policy;
        this.factories = factories;
        this.convert = convert;
    }

    @Override
    @Transactional(readOnly = true)
    public SnContextVO current() {
        var actor = policy.read(SnQueryPolicy.DASHBOARD);
        var scope = policy.basic(actor, SnQueryPolicy.DASHBOARD);
        var generation = actor.grants().get("sn:generation:execute");
        String role =
                generation != null && generation.allFactories()
                        ? "admin"
                        : actor.permissions().contains(AcquirePolicy.TAKE)
                                ? "factory_operator"
                                : "query";
        // 仅查经当前首页授权确认的工厂；全厂账户无需传输完整目录。
        var bound =
                scope.all()
                        ? List.<com.adtek.sn.vo.FactoryVO>of()
                        : scope.factories().stream()
                                .map(factories::selectById)
                                .filter(Objects::nonNull)
                                .map(convert::factory)
                                .toList();
        return new SnContextVO(role, scope.all(), bound);
    }
}
