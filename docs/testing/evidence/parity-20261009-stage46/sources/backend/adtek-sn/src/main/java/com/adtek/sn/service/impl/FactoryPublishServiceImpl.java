package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.core.*;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.sn.convert.SnConvert;
import com.adtek.sn.domain.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.FactoryPublishService;
import com.adtek.sn.service.port.ErpProductionOrderPort.*;
import com.adtek.sn.service.support.SnPolicy;
import com.adtek.sn.vo.*;

import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 安全锁→快照发布锁→工厂；目录按编码合并，订单按快照名称匹配，显式绑定不被同步覆盖。 */
@Service
public class FactoryPublishServiceImpl implements FactoryPublishService {
    private final SnPolicy policy;
    private final SnControlMapper control;
    private final FactoryMapper factories;
    private final OrganizationMappingMapper mappings;
    private final SnConvert convert;
    private final ManagementAuditPort audit;
    private final com.adtek.framework.security.AuthorizationMutationPort authorization;

    public FactoryPublishServiceImpl(
            SnPolicy policy,
            SnControlMapper control,
            FactoryMapper factories,
            OrganizationMappingMapper mappings,
            SnConvert convert,
            ManagementAuditPort audit,
            com.adtek.framework.security.AuthorizationMutationPort authorization) {
        this.policy = policy;
        this.control = control;
        this.factories = factories;
        this.mappings = mappings;
        this.convert = convert;
        this.audit = audit;
        this.authorization = authorization;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FactorySyncVO publish(Source source, List<Organization> orgs) {
        var actor = policy.lock("sn:factory:sync");
        long revision = control.lock() + 1;
        var result = merge(source, orgs, revision);
        control.advance();
        syncSuccess(actor, source, result);
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FactorySyncVO withinOrderPublish(
            Actor requested, Source source, List<Organization> orgs, long revision) {
        // HTTP与定时执行共享显式主体；不依赖调度线程没有的SecurityContext。
        var actor = authorization.lockCurrent(requested);
        policy.require(actor, "sn:factory:sync", true);
        var result = merge(source, orgs, revision);
        syncSuccess(actor, source, result);
        return result;
    }

    private FactorySyncVO merge(Source source, List<Organization> orgs, long revision) {
        var links = new HashMap<String, OrganizationMappingEntity>();
        for (var link : mappings.list(source.system(), source.account()))
            links.put(link.orgId(), link);
        int inserted = 0, updated = 0;
        List<String> conflicts = new ArrayList<>();
        for (var org : orgs) {
            var factory = factories.byCode(org.code());
            var sameName = factories.byName(org.name());
            if (sameName != null
                    && (factory == null || !sameName.getId().equals(factory.getId()))) {
                conflicts.add(org.code() + "/" + org.name());
                continue;
            }
            if (factory == null) {
                factory = new FactoryEntity();
                factory.setCode(org.code());
                factory.setName(org.name());
                factory.setSource("K3");
                factory.setEnabled(true);
                factory.setVersion(1L);
                factory.setCreatedAt(Instant.now());
                factory.setUpdatedAt(factory.getCreatedAt());
                factories.insert(factory);
                inserted++;
            } else if (!factory.getName().equals(org.name())) {
                factory.setName(org.name());
                factory.setVersion(factory.getVersion() + 1);
                factory.setUpdatedAt(Instant.now());
                factories.updateById(factory);
                updated++;
            }
            // 内码只补充来源信息；替换内码、改编码不会把旧名称订单搬到另一个工厂。
            if (org.id() == null) continue;
            var old = links.get(org.id());
            if (old != null && old.explicit()) continue;
            var link =
                    new OrganizationMappingEntity(
                            source.system(),
                            source.account(),
                            org.id(),
                            org.code(),
                            org.name(),
                            factory.getId(),
                            "AUTO");
            if (old == null) mappings.insert(link);
            else if (!old.equals(link)) mappings.update(link);
            links.put(org.id(), link);
        }
        mappings.projectAutomaticBills(revision);
        mappings.projectAutomaticEntries(revision);
        return new FactorySyncVO(
                Integer.toString(orgs.size()),
                Integer.toString(inserted),
                Integer.toString(updated),
                List.copyOf(conflicts));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FactoryVO bind(Source source, Organization org, long factoryId, long version) {
        var actor = policy.lock("sn:factory:map");
        long revision = control.lock() + 1;
        var factory = factories.lock(factoryId);
        if (factory == null) throw SnPolicy.missing();
        if (factory.getVersion() != version) throw new BusinessException(VERSION_CHANGED);
        var links = mappings.list(source.system(), source.account());
        OrganizationMappingEntity previous = null;
        for (var link : links) {
            if (link.orgId().equals(org.id())) previous = link;
            if (!link.explicit()) continue;
            if (link.orgId().equals(org.id()) && link.factoryId() == factoryId)
                return convert.factory(factory);
            if (link.orgId().equals(org.id())
                    || link.orgCode().equals(org.code())
                    || link.factoryId() == factoryId) throw new BusinessException(MAPPING_CONFLICT);
        }
        try {
            var link =
                    new OrganizationMappingEntity(
                            source.system(),
                            source.account(),
                            org.id(),
                            org.code(),
                            org.name(),
                            factoryId,
                            "EXPLICIT");
            if (previous == null) mappings.insert(link);
            else mappings.update(link);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(MAPPING_CONFLICT);
        }
        factory.setVersion(version + 1);
        factory.setUpdatedAt(Instant.now());
        factories.updateById(factory);
        project(source, org.id(), factory, revision);
        control.advance();
        success(
                actor,
                "sn:factory:map",
                Long.toString(factoryId),
                Map.of(
                        "sourceSystem",
                        source.system(),
                        "sourceAccount",
                        source.account(),
                        "orgId",
                        org.id()));
        return convert.factory(factory);
    }

    private void project(Source source, String orgId, FactoryEntity factory, long revision) {
        mappings.mapBills(
                source.system(),
                source.account(),
                orgId,
                factory.getId(),
                factory.getCode(),
                revision);
        mappings.mapEntries(source.system(), source.account(), orgId, revision);
    }

    /** 工厂同步即使没有变化仍记录原型计数；与业务发布在同一事务中提交。 */
    private void syncSuccess(Actor actor, Source source, FactorySyncVO result) {
        success(
                actor,
                "sn:factory:sync",
                source.system(),
                Map.of(
                        "total",
                        result.total(),
                        "inserted",
                        result.inserted(),
                        "updated",
                        result.updated(),
                        "conflicts",
                        result.conflicts()));
    }

    private void success(Actor actor, String action, String object, Map<String, Object> changes) {
        audit.success(
                new ManagementAuditPort.Event(
                        actor.userId(),
                        "工厂管理",
                        action,
                        "UPDATE",
                        object,
                        changes,
                        MDC.get("traceId")));
    }
}
