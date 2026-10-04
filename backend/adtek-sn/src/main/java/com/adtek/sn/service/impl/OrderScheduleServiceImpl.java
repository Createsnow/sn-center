package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.framework.security.*;
import com.adtek.sn.convert.SnConvert;
import com.adtek.sn.dto.OrderScheduleDTO;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.*;
import com.adtek.sn.service.support.SnPolicy;
import com.adtek.sn.vo.OrderScheduleVO;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.Map;

@Service
public class OrderScheduleServiceImpl implements OrderScheduleService {
    private final SnPolicy policy;
    private final CommandValidator validator;
    private final OrderScheduleMapper schedules;
    private final SnControlMapper control;
    private final SnConvert convert;
    private final OrderScheduleControl runs;
    private final AuthorizationPort authorizations;
    private final OrderService orders;
    private final OrderPublishService publisher;
    private final ManagementAuditPort audit;

    @org.springframework.beans.factory.annotation.Value(
            "${adtek.sn.order-sync-worker.enabled:true}")
    private boolean workerEnabled;

    public OrderScheduleServiceImpl(
            SnPolicy policy,
            CommandValidator validator,
            OrderScheduleMapper schedules,
            SnControlMapper control,
            SnConvert convert,
            OrderScheduleControl runs,
            AuthorizationPort authorizations,
            OrderService orders,
            OrderPublishService publisher,
            ManagementAuditPort audit) {
        this.policy = policy;
        this.validator = validator;
        this.schedules = schedules;
        this.control = control;
        this.convert = convert;
        this.runs = runs;
        this.authorizations = authorizations;
        this.orders = orders;
        this.publisher = publisher;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderScheduleVO status() {
        policy.read("sn:order:list", true);
        return convert.schedule(schedules.get(), workerEnabled);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderScheduleVO save(OrderScheduleDTO command) {
        validator.requireValid(command);
        var actor = policy.lock("sn:order:sync");
        control.lock();
        var before = schedules.lock();
        boolean changed =
                before.enabled() != command.enabled()
                        || before.intervalMinutes() != command.intervalMinutes()
                        || !java.util.Objects.equals(before.ownerId(), actor.userId());
        if (changed) runs.invalidate(before);
        var next =
                !command.enabled()
                        ? null
                        : changed || before.nextRunAt() == null
                                ? schedules.now().plusSeconds(command.intervalMinutes() * 60L)
                                : before.nextRunAt();
        schedules.save(command.enabled(), command.intervalMinutes(), next, actor.userId());
        audit.success(
                new ManagementAuditPort.Event(
                        actor.userId(),
                        "生产订单",
                        "sn:order:schedule",
                        "UPDATE",
                        "1",
                        Map.of(
                                "enabled",
                                Map.of("before", before.enabled(), "after", command.enabled()),
                                "intervalMinutes",
                                Map.of(
                                        "before",
                                        before.intervalMinutes(),
                                        "after",
                                        command.intervalMinutes())),
                        MDC.get("traceId")));
        return convert.schedule(schedules.get(), workerEnabled);
    }

    @Override
    public boolean runDue() {
        var attempt = runs.claim();
        if (attempt == null) return false;
        try {
            var actor =
                    authorizations
                            .loadCurrent(attempt.getActorId())
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    com.adtek.framework.constant.ErrorCodeConstants
                                                            .FORBIDDEN));
            policy.require(actor, "sn:order:sync", true);
            orders.synchronizeScheduled(actor, attempt.getId());
        } catch (RuntimeException error) {
            int code =
                    error instanceof BusinessException business
                            ? business.getError().code()
                            : com.adtek.sn.constant.ErrorCodeConstants.SYNC_FAILED.code();
            publisher.failed(attempt.getId(), code);
        }
        return true;
    }
}
