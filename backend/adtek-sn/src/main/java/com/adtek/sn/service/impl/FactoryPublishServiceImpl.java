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

/** 安全锁→快照发布锁→工厂；外部网络已完成，冲突不能改派组织或认领同名手工厂。 */
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
        success(actor, "sn:factory:sync", source.system(), Map.of());
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FactorySyncVO withinOrderPublish(
            Actor requested, Source source, List<Organization> orgs, long revision) {
        // HTTP与定时执行共享显式主体；不依赖调度线程没有的SecurityContext。
        policy.require(authorization.lockCurrent(requested), "sn:factory:sync", true);
        return merge(source, orgs, revision);
    }

    private FactorySyncVO merge(Source source, List<Organization> orgs, long revision) {
        var links = new HashMap<String, OrganizationMappingEntity>();
        for (var link : mappings.list(source.system(), source.account()))
            links.put(link.orgId(), link);
        int inserted = 0, updated = 0;
        List<String> conflicts = new ArrayList<>();
        for (var org : orgs) {
            var old = links.get(org.id());
            var sameName = factories.byName(org.name());
            if (old != null) {
                var factory = factories.lock(old.factoryId());
                if (factory == null) throw new BusinessException(MAPPING_CONFLICT);
                if ((sameName != null && !sameName.getId().equals(factory.getId()))
                        || links.values().stream()
                                .anyMatch(
                                        l ->
                                                !l.orgId().equals(org.id())
                                                        && l.orgCode().equals(org.code()))) {
                    conflicts.add(org.code() + "/" + org.name());
                    continue;
                }
                boolean changed =
                        !factory.getName().equals(org.name())
                                || !old.orgCode().equals(org.code())
                                || !old.orgName().equals(org.name());
                if (changed) {
                    factory.setName(org.name());
                    factory.setVersion(factory.getVersion() + 1);
                    factory.setUpdatedAt(Instant.now());
                    factories.updateById(factory);
                    mappings.update(
                            new OrganizationMappingEntity(
                                    source.system(),
                                    source.account(),
                                    org.id(),
                                    org.code(),
                                    org.name(),
                                    factory.getId()));
                    updated++;
                }
                project(source, org.id(), factory, revision);
                continue;
            }
            if (factories.byCode(org.code()) != null
                    || sameName != null
                    || links.values().stream().anyMatch(l -> l.orgCode().equals(org.code()))) {
                conflicts.add(org.code() + "/" + org.name());
                continue;
            }
            var factory = new FactoryEntity();
            factory.setCode(org.code());
            factory.setName(org.name());
            factory.setSource("K3");
            factory.setEnabled(true);
            factory.setVersion(1L);
            factory.setCreatedAt(Instant.now());
            factory.setUpdatedAt(factory.getCreatedAt());
            factories.insert(factory);
            var link =
                    new OrganizationMappingEntity(
                            source.system(),
                            source.account(),
                            org.id(),
                            org.code(),
                            org.name(),
                            factory.getId());
            mappings.insert(link);
            links.put(org.id(), link);
            project(source, org.id(), factory, revision);
            inserted++;
        }
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
        for (var link : links) {
            if (link.orgId().equals(org.id()) && link.factoryId() == factoryId)
                return convert.factory(factory);
            if (link.orgId().equals(org.id())
                    || link.orgCode().equals(org.code())
                    || link.factoryId() == factoryId) throw new BusinessException(MAPPING_CONFLICT);
        }
        try {
            mappings.insert(
                    new OrganizationMappingEntity(
                            source.system(),
                            source.account(),
                            org.id(),
                            org.code(),
                            org.name(),
                            factoryId));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(MAPPING_CONFLICT);
        }
        factory.setVersion(version + 1);
        factory.setUpdatedAt(Instant.now());
        factories.updateById(factory);
        project(source, org.id(), factory, revision);
        control.advance();
        success(actor, "sn:factory:map", Long.toString(factoryId), Map.of());
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
