package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.core.*;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.sn.convert.SnConvert;
import com.adtek.sn.domain.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.*;
import com.adtek.sn.service.port.ErpProductionOrderPort.*;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.OrderSyncVO;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 拉取成功后只在本地事务写；安全锁→发布锁，先比较基准再替换范围，历史额度只初始化不清空。 */
@Service
public class OrderPublishServiceImpl implements OrderPublishService {
    private final SnPolicy policy;
    private final OrderPublicationGuard publicationGuard;
    private final SnControlMapper control;
    private final OrderSyncMapper attempts;
    private final OrderBillMapper bills;
    private final OrderEntryMapper entries;
    private final SourceQuotaMapper quotas;
    private final OrganizationMappingMapper mappings;
    private final FactoryMapper factories;
    private final ManagementAuditPort audit;
    private final SnConvert convert;
    private final OrderScheduleMapper schedules;

    public OrderPublishServiceImpl(
            SnPolicy policy,
            OrderPublicationGuard publicationGuard,
            SnControlMapper control,
            OrderSyncMapper attempts,
            OrderBillMapper bills,
            OrderEntryMapper entries,
            SourceQuotaMapper quotas,
            OrganizationMappingMapper mappings,
            FactoryMapper factories,
            ManagementAuditPort audit,
            SnConvert convert,
            OrderScheduleMapper schedules) {
        this.policy = policy;
        this.publicationGuard = publicationGuard;
        this.control = control;
        this.attempts = attempts;
        this.bills = bills;
        this.entries = entries;
        this.quotas = quotas;
        this.mappings = mappings;
        this.factories = factories;
        this.audit = audit;
        this.convert = convert;
        this.schedules = schedules;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public OrderSyncVO begin(String billNo) {
        var actor = policy.lock("sn:order:sync");
        long base = control.lock();
        var attempt = new OrderSyncEntity();
        attempt.setId(UUID.randomUUID().toString());
        attempt.setActorId(actor.userId());
        attempt.setMode(billNo == null ? "all" : "one");
        attempt.setBillNo(billNo == null ? "" : billNo);
        attempt.setState("FETCHING");
        attempt.setBaseVersion(base);
        attempt.setRowsCount(0L);
        attempt.setBillsCount(0L);
        attempt.setOrgSyncFailed(false);
        attempt.setStartedAt(Instant.now());
        attempts.insert(attempt);
        return convert.sync(attempt);
    }

    @Override
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            timeout = 60,
            rollbackFor = Exception.class)
    public OrderSyncVO publish(
            Actor requested, String id, Source source, List<Row> rows, boolean orgFailed) {
        var locked = publicationGuard.lock(requested, id);
        var actor = locked.actor();
        long previous = locked.revision();
        var attempt = locked.attempt();
        String billNo = attempt.getMode().equals("one") ? attempt.getBillNo() : null;
        var groups = OrderSnapshot.validate(rows, billNo);
        long version = previous + 1;
        var links = new HashMap<String, OrganizationMappingEntity>();
        for (var link : mappings.list(source.system(), source.account()))
            links.put(link.orgId(), link);
        entries.deactivateSource(source.system(), source.account(), billNo, version);
        bills.deactivate(source.system(), source.account(), billNo, version);
        Instant now = Instant.now();
        for (var group : groups.values()) {
            var first = group.getFirst();
            var bill = bills.source(source.system(), source.account(), first.billId());
            boolean fresh = bill == null;
            if (fresh) bill = new OrderBillEntity();
            bill.setSourceSystem(source.system());
            bill.setSourceAccount(source.account());
            bill.setSourceBillId(first.billId());
            bill.setBillNo(first.billNo());
            bill.setCustomer(first.customer());
            bill.setPo(first.po());
            bill.setPi(first.pi());
            bill.setOrgId(first.orgId());
            bill.setOrgCode(first.orgCode());
            bill.setOrgName(first.orgName());
            var link = links.get(first.orgId());
            var factory =
                    link != null && link.explicit()
                            ? factories.selectById(link.factoryId())
                            : factories.byName(first.orgName());
            if (link != null && link.explicit() && factory == null)
                throw new BusinessException(MAPPING_CONFLICT);
            bill.setFactoryId(factory == null ? null : factory.getId());
            bill.setFactoryCode(factory == null ? null : factory.getCode());
            bill.setActive(true);
            bill.setTotalQty(
                    group.stream()
                            .map(
                                    row ->
                                            java.math.BigDecimal.valueOf(
                                                    PlannedQuantity.snCount(row.qty())))
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
            bill.setQuantityReady(true);
            bill.setLineCount((long) group.size());
            bill.setStatuses(
                    String.join(",", group.stream().map(Row::status).distinct().sorted().toList()));
            bill.setSnapshotVersion(version);
            bill.setSyncedAt(now);
            if (fresh) bills.insert(bill);
            else bills.updateById(bill);
            long ordinal = 0;
            for (var row : group) {
                var entry = entries.source(bill.getId(), row.entryId());
                boolean newEntry = entry == null;
                if (newEntry) entry = new OrderEntryEntity();
                entry.setBillId(bill.getId());
                entry.setSourceEntryId(row.entryId());
                entry.setOrdinal(++ordinal);
                entry.setMaterial(row.material());
                entry.setMaterialName(row.materialName());
                entry.setQty(row.qty());
                entry.setStatus(row.status());
                entry.setDemandBillNo(row.demandBillNo());
                entry.setFactoryId(bill.getFactoryId());
                entry.setActive(true);
                entry.setSnapshotVersion(version);
                if (newEntry) entries.insert(entry);
                else entries.updateById(entry);
                quotas.ensure(bill.getId(), row.entryId());
            }
        }
        control.advance();
        attempt.setState("SUCCESS");
        attempt.setPublishedVersion(version);
        attempt.setRowsCount((long) rows.size());
        attempt.setBillsCount((long) groups.size());
        attempt.setOrgSyncFailed(orgFailed);
        attempt.setFinishedAt(now);
        attempts.updateById(attempt);
        if ("scheduled".equals(attempt.getMode()))
            schedules.succeeded(id, attempt.getBillsCount(), attempt.getRowsCount());
        audit.success(
                new ManagementAuditPort.Event(
                        actor.userId(),
                        "生产订单",
                        "sn:order:sync",
                        "UPDATE",
                        id,
                        Map.of(),
                        MDC.get("traceId")));
        return convert.sync(attempt);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void failed(String id, int errorCode, String safeMessage) {
        // 与发布使用相同协调锁，失败回写不能根据旧FETCHING快照覆盖已提交SUCCESS。
        control.lock();
        var attempt = attempts.selectById(id);
        if (attempt == null || !attempt.getState().equals("FETCHING")) return;
        attempt.setState("FAILED");
        attempt.setErrorCode(errorCode);
        attempt.setFinishedAt(Instant.now());
        attempts.updateById(attempt);
        if ("scheduled".equals(attempt.getMode())) schedules.failed(id, errorCode, safeMessage);
        try {
            audit.failure(
                    new ManagementAuditPort.Event(
                            attempt.getActorId(),
                            "生产订单",
                            "sn:order:sync:failed",
                            "OTHER",
                            id,
                            Map.of("errorCode", Integer.toString(errorCode)),
                            MDC.get("traceId")));
            com.adtek.framework.audit.RequestAuditState.failureCommitted();
        } catch (RuntimeException failure) {
            // 失败审计是独立事务；存储故障不能撤销已经确定的同步失败状态，也不记录ERP载荷。
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn(
                            "SN failure audit unavailable traceId={} type={}",
                            MDC.get("traceId"),
                            failure.getClass().getSimpleName());
        }
    }
}
