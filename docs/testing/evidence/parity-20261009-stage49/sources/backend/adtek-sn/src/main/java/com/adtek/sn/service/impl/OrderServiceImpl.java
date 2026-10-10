package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.annotation.DataScope;
import com.adtek.common.core.*;
import com.adtek.framework.security.CommandValidator;
import com.adtek.sn.convert.SnConvert;
import com.adtek.sn.domain.*;
import com.adtek.sn.dto.OrderSyncDTO;
import com.adtek.sn.mapper.*;
import com.adtek.sn.query.OrderCandidateQuery;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.service.*;
import com.adtek.sn.service.port.ErpProductionOrderPort;
import com.adtek.sn.service.support.FactoryText;
import com.adtek.sn.service.support.PlannedQuantity;
import com.adtek.sn.service.support.SnPolicy;
import com.adtek.sn.vo.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigInteger;
import java.util.*;

@Service
public class OrderServiceImpl implements OrderService {
    private static final Logger LOG = LoggerFactory.getLogger(OrderServiceImpl.class);
    private final SnPolicy policy;
    private final CommandValidator validator;
    private final OrderBillMapper bills;
    private final OrderCandidateMapper candidates;
    private final OrderEntryMapper entries;
    private final SourceQuotaMapper quotas;
    private final OrderSyncMapper attempts;
    private final ErpProductionOrderPort erp;
    private final OrderPublishService publisher;
    private final FactoryPublishService factoryPublisher;
    private final SnConvert convert;

    public OrderServiceImpl(
            SnPolicy policy,
            CommandValidator validator,
            OrderBillMapper bills,
            OrderCandidateMapper candidates,
            OrderEntryMapper entries,
            SourceQuotaMapper quotas,
            OrderSyncMapper attempts,
            ErpProductionOrderPort erp,
            OrderPublishService publisher,
            FactoryPublishService factoryPublisher,
            SnConvert convert) {
        this.policy = policy;
        this.validator = validator;
        this.bills = bills;
        this.candidates = candidates;
        this.entries = entries;
        this.quotas = quotas;
        this.attempts = attempts;
        this.erp = erp;
        this.publisher = publisher;
        this.factoryPublisher = factoryPublisher;
        this.convert = convert;
    }

    @Override
    @Transactional(readOnly = true)
    @DataScope(resource = "sn:order", permission = "sn:order:list")
    public PageResult<OrderBillVO> list(SnPageQuery query) {
        validator.requireValid(query);
        var filter = candidateQuery(query);
        var records = candidates.bills(filter);
        long total = candidates.billCount(filter);
        var ids = records.stream().map(OrderBillEntity::getId).toList();
        if (ids.isEmpty())
            return new PageResult<>(
                    List.of(), Long.toString(total), query.getPage(), query.getSize());
        var history = quotas.forBills(ids);
        var result =
                records.stream()
                        .map(
                                bill -> {
                                    var ledger =
                                            history.stream()
                                                    .filter(q -> q.billId() == bill.getId())
                                                    .toList();
                                    BigInteger
                                            generated =
                                                    ledger.stream()
                                                            .map(
                                                                    q ->
                                                                            BigInteger.valueOf(
                                                                                    q
                                                                                            .generatedQty()))
                                                            .reduce(
                                                                    BigInteger.ZERO,
                                                                    BigInteger::add),
                                            allocated =
                                                    ledger.stream()
                                                            .map(
                                                                    q ->
                                                                            BigInteger.valueOf(
                                                                                    q
                                                                                            .allocatedQty()))
                                                            .reduce(
                                                                    BigInteger.ZERO,
                                                                    BigInteger::add);
                                    BigInteger remaining =
                                            bill.getTotalQty()
                                                    .toBigIntegerExact()
                                                    .subtract(generated)
                                                    .max(BigInteger.ZERO);
                                    return convert.bill(
                                            bill,
                                            generated.toString(),
                                            allocated.toString(),
                                            Boolean.TRUE.equals(bill.getQuantityReady())
                                                    ? remaining.toString()
                                                    : "0");
                                })
                        .toList();
        return new PageResult<>(result, Long.toString(total), query.getPage(), query.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    @DataScope(resource = "sn:order", permission = "sn:order:list")
    public PageResult<OrderLineVO> lines(String id, SnPageQuery query) {
        validator.requireValid(query);
        long billId = SnPolicy.id(id);
        var bill = bills.selectById(billId);
        if (bill == null || !bill.getActive() || bill.getCustomer().isEmpty())
            throw SnPolicy.missing();
        var page =
                entries.selectPage(
                        new Page<>(query.getPage(), query.getSize()),
                        Wrappers.<OrderEntryEntity>lambdaQuery()
                                .eq(OrderEntryEntity::getBillId, billId)
                                .eq(OrderEntryEntity::getActive, true)
                                .orderByAsc(OrderEntryEntity::getOrdinal, OrderEntryEntity::getId));
        var ledger = quotas.forBills(List.of(billId));
        var result =
                page.getRecords().stream()
                        .map(
                                e -> {
                                    var q =
                                            ledger.stream()
                                                    .filter(
                                                            l ->
                                                                    l.sourceEntryId()
                                                                            .equals(
                                                                                    e
                                                                                            .getSourceEntryId()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    return convert.line(
                                            e,
                                            Long.toString(q.generatedQty()),
                                            Long.toString(q.allocatedQty()),
                                            Long.toString(remaining(e, ledger)));
                                })
                        .toList();
        return new PageResult<>(
                result, Long.toString(page.getTotal()), query.getPage(), query.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    @DataScope(resource = "sn:order", permission = "sn:order:list")
    public OrderMetaVO meta() {
        policy.read("sn:order:list", false);
        var stats = bills.stats();
        var unmapped = bills.unmapped();
        if (unmapped.size() > 1000) throw new BusinessException(RESOURCE_LIMIT);
        return new OrderMetaVO(
                stats.syncedAt(),
                Long.toString(stats.bills()),
                Long.toString(stats.rowsCount()),
                Long.toString(stats.excluded()),
                unmapped.stream()
                        .map(OrderBillMapper.Unmapped::orgName)
                        .distinct()
                        .sorted()
                        .toList());
    }

    @Override
    @Transactional(readOnly = true)
    @DataScope(resource = "sn:order", permission = "sn:order:list")
    public PageResult<String> customers(SnPageQuery query) {
        validator.requireValid(query);
        String like = SnPolicy.like(query.getQ());
        return new PageResult<>(
                bills.customers(like, (query.getPage() - 1) * query.getSize(), query.getSize()),
                Long.toString(bills.customerCount(like)),
                query.getPage(),
                query.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<OrderPiVO> pis(SnPageQuery query) {
        validator.requireValid(query);
        var filter = candidateQuery(query);
        return new PageResult<>(
                candidates.pis(filter).stream().map(convert::pi).toList(),
                Long.toString(candidates.piCount(filter)),
                query.getPage(),
                query.getSize());
    }

    private OrderCandidateQuery candidateQuery(SnPageQuery query) {
        var grant = policy.read("sn:order:list", false).grants().get("sn:order:list");
        return new OrderCandidateQuery(
                SnPolicy.like(query.getQ()),
                SnPolicy.optional(query.getCustomer()),
                SnPolicy.optional(query.getPi()),
                SnPolicy.optional(query.getFactoryId()) == null
                        ? null
                        : SnPolicy.id(query.getFactoryId()),
                query.isPending(),
                grant.allFactories(),
                grant.factoryIds(),
                (query.getPage() - 1) * query.getSize(),
                query.getSize());
    }

    @Override
    public OrderSyncVO synchronize(OrderSyncDTO command) {
        validator.requireValid(command);
        var actor = policy.read("sn:order:sync", true);
        // 只有缺省值表示全量；空白单号必须在任何ERP请求/持久同步之前拒绝。
        String no = command.billNo() == null ? null : FactoryText.trim(command.billNo());
        if (no != null && no.isEmpty()) throw new BusinessException(ORDER_BILL_REQUIRED);
        var attempt = publisher.begin(no);
        return fetchAndPublish(actor, attempt.id(), no);
    }

    @Override
    public OrderSyncVO synchronizeScheduled(Actor actor, String runId) {
        policy.require(actor, "sn:order:sync", true);
        var attempt = attempts.selectById(runId);
        if (attempt == null
                || !"scheduled".equals(attempt.getMode())
                || !"FETCHING".equals(attempt.getState())
                || attempt.getActorId() != actor.userId())
            throw new BusinessException(SYNC_EXECUTION_EXPIRED);
        return fetchAndPublish(actor, runId, null);
    }

    private OrderSyncVO fetchAndPublish(Actor actor, String attemptId, String no) {
        try {
            var source = erp.source();
            var rows = erp.orders(no);
            boolean orgFailed = false;
            if (no == null
                    && actor.grants().containsKey("sn:factory:sync")
                    && actor.grants().get("sn:factory:sync").allFactories()) {
                try {
                    factoryPublisher.publishForOrderSync(
                            actor, attemptId, source, erp.organizations());
                } catch (RuntimeException e) {
                    // 原型吞掉附带工厂同步失败；后续订单事务仍复核主体、基准和租约。
                    orgFailed = true;
                    LOG.warn(
                            "SN attached factory sync failed traceId={} exceptionType={}",
                            MDC.get("traceId"),
                            e.getClass().getSimpleName());
                }
            }
            return publisher.publish(actor, attemptId, source, rows, orgFailed);
        } catch (RuntimeException error) {
            int code =
                    error instanceof BusinessException business
                            ? business.getError().code()
                            : SYNC_FAILED.code();
            try {
                publisher.failed(
                        attemptId,
                        code,
                        error instanceof BusinessException business
                                ? business.getParams().getOrDefault("message", "")
                                : "");
            } catch (RuntimeException failure) {
                LOG.error(
                        "SN sync failure record unavailable traceId={} exceptionType={}",
                        MDC.get("traceId"),
                        failure.getClass().getSimpleName());
            }
            throw error;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<OrderSyncVO> syncs(SnPageQuery query) {
        validator.requireValid(query);
        policy.read("sn:order:list", true);
        var page =
                attempts.selectPage(
                        new Page<>(query.getPage(), query.getSize()),
                        Wrappers.<OrderSyncEntity>lambdaQuery()
                                .orderByDesc(
                                        OrderSyncEntity::getStartedAt, OrderSyncEntity::getId));
        return new PageResult<>(
                page.getRecords().stream().map(convert::sync).toList(),
                Long.toString(page.getTotal()),
                query.getPage(),
                query.getSize());
    }

    private static long remaining(OrderEntryEntity entry, List<SourceQuotaMapper.Quota> quotas) {
        long generated =
                quotas.stream()
                        .filter(
                                q ->
                                        q.billId() == entry.getBillId()
                                                && q.sourceEntryId()
                                                        .equals(entry.getSourceEntryId()))
                        .mapToLong(SourceQuotaMapper.Quota::generatedQty)
                        .findFirst()
                        .orElseThrow();
        return PlannedQuantity.remaining(entry.getQty(), generated);
    }
}
