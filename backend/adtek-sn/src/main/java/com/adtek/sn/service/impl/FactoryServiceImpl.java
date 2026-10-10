package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.annotation.*;
import com.adtek.common.annotation.DataScope;
import com.adtek.common.core.*;
import com.adtek.framework.audit.AuditContext;
import com.adtek.framework.security.CommandValidator;
import com.adtek.sn.convert.SnConvert;
import com.adtek.sn.dto.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.service.*;
import com.adtek.sn.service.port.ErpProductionOrderPort;
import com.adtek.sn.service.support.SnPolicy;
import com.adtek.sn.vo.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
public class FactoryServiceImpl implements FactoryService {
    private final FactoryMapper factories;
    private final OrganizationMappingMapper mappings;
    private final SnControlMapper control;
    private final SnPolicy policy;
    private final CommandValidator validator;
    private final SnConvert convert;
    private final ErpProductionOrderPort erp;
    private final FactoryPublishService publisher;

    public FactoryServiceImpl(
            FactoryMapper factories,
            OrganizationMappingMapper mappings,
            SnControlMapper control,
            SnPolicy policy,
            CommandValidator validator,
            SnConvert convert,
            ErpProductionOrderPort erp,
            FactoryPublishService publisher) {
        this.factories = factories;
        this.mappings = mappings;
        this.control = control;
        this.policy = policy;
        this.validator = validator;
        this.convert = convert;
        this.erp = erp;
        this.publisher = publisher;
    }

    @Override
    @Transactional(readOnly = true)
    @DataScope(resource = "sn:factory", permission = "sn:factory:list")
    public PageResult<FactoryVO> list(SnPageQuery query) {
        validator.requireValid(query);
        var where =
                Wrappers.<com.adtek.sn.domain.FactoryEntity>lambdaQuery()
                        .orderByAsc(
                                com.adtek.sn.domain.FactoryEntity::getCode,
                                com.adtek.sn.domain.FactoryEntity::getId);
        String like = SnPolicy.like(query.getQ());
        if (like != null)
            where.and(
                    w ->
                            w.apply("code LIKE {0} ESCAPE '!'", like)
                                    .or()
                                    .apply("name LIKE {0} ESCAPE '!'", like));
        var page = factories.selectPage(new Page<>(query.getPage(), query.getSize()), where);
        return new PageResult<>(
                page.getRecords().stream().map(convert::factory).toList(),
                Long.toString(page.getTotal()),
                query.getPage(),
                query.getSize());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @OperLog(
            title = "工厂管理",
            action = "sn:factory:create",
            businessType = OperLog.BusinessType.INSERT,
            fields = {"code", "name", "source"})
    public FactoryVO create(FactoryCreateDTO input) {
        policy.read("sn:factory:create", true);
        var command =
                new FactoryCreateDTO(
                        com.adtek.sn.service.support.FactoryText.trim(input.code()),
                        com.adtek.sn.service.support.FactoryText.trim(input.name()));
        if (command.code().isEmpty()
                || command.name().isEmpty()
                || command.code().codePointCount(0, command.code().length()) > 64
                || command.name().codePointCount(0, command.name().length()) > 128)
            throw new BusinessException(FACTORY_INVALID);
        policy.lock("sn:factory:create");
        long revision = control.lock() + 1;
        var entity = convert.factory(command);
        entity.setSource("MANUAL");
        entity.setEnabled(true);
        entity.setVersion(1L);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        try {
            factories.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    FACTORY_EXISTS, Map.of("factory", command.code() + "/" + command.name()));
        }
        mappings.projectAutomaticBills(revision);
        mappings.projectAutomaticEntries(revision);
        control.advance();
        AuditContext.record(
                entity.getId().toString(),
                Map.of(),
                Map.of(
                        "code",
                        entity.getCode(),
                        "name",
                        entity.getName(),
                        "source",
                        entity.getSource()));
        return convert.factory(entity);
    }

    @Override
    public FactorySyncVO synchronize() {
        policy.read("sn:factory:sync", true);
        var source = erp.source();
        return publisher.publish(source, erp.organizations());
    }

    @Override
    public PageResult<OrganizationVO> organizations(SnPageQuery query) {
        validator.requireValid(query);
        policy.read("sn:factory:map", true);
        var source = erp.source();
        var orgs = erp.organizations();
        var links = new HashMap<String, String>();
        for (var link : mappings.list(source.system(), source.account()))
            links.put(link.orgId(), Long.toString(link.factoryId()));
        String q = SnPolicy.text(query.getQ());
        var all =
                orgs.stream()
                        .filter(o -> o.id() != null)
                        .filter(o -> q.isEmpty() || o.code().contains(q) || o.name().contains(q))
                        .sorted(
                                Comparator.comparing(ErpProductionOrderPort.Organization::code)
                                        .thenComparing(ErpProductionOrderPort.Organization::id))
                        .toList();
        int start = Math.min(all.size(), (query.getPage() - 1) * query.getSize());
        var items =
                all.subList(start, Math.min(all.size(), start + query.getSize())).stream()
                        .map(o -> new OrganizationVO(o.id(), o.code(), o.name(), links.get(o.id())))
                        .toList();
        return new PageResult<>(
                items, Integer.toString(all.size()), query.getPage(), query.getSize());
    }

    @Override
    public FactoryVO bind(String factoryId, OrganizationMapDTO command) {
        validator.requireValid(command);
        policy.read("sn:factory:map", true);
        var source = erp.source();
        var org =
                erp.organizations().stream()
                        .filter(o -> command.orgId().equals(o.id()))
                        .findFirst()
                        .orElseThrow(SnPolicy::missing);
        return publisher.bind(source, org, SnPolicy.id(factoryId), SnPolicy.id(command.version()));
    }
}
