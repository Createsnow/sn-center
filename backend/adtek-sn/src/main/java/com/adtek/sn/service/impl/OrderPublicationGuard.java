package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.core.Actor;
import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.AuthorizationMutationPort;
import com.adtek.sn.domain.OrderSyncEntity;
import com.adtek.sn.mapper.OrderScheduleMapper;
import com.adtek.sn.mapper.OrderSyncMapper;
import com.adtek.sn.mapper.SnControlMapper;
import com.adtek.sn.service.support.SnPolicy;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 工厂与订单事务各自复核同一主体、快照基准和调度租约；锁顺序为安全→发布→调度。 */
@Component
public class OrderPublicationGuard {
    private final SnPolicy policy;
    private final AuthorizationMutationPort authorization;
    private final SnControlMapper control;
    private final OrderSyncMapper attempts;
    private final OrderScheduleMapper schedules;

    public OrderPublicationGuard(
            SnPolicy policy,
            AuthorizationMutationPort authorization,
            SnControlMapper control,
            OrderSyncMapper attempts,
            OrderScheduleMapper schedules) {
        this.policy = policy;
        this.authorization = authorization;
        this.control = control;
        this.attempts = attempts;
        this.schedules = schedules;
    }

    /** 必须由实际发布事务调用；仅本次工厂提交能推进本次订单的基准，其他提交仍视为冲突。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Locked lock(Actor requested, String id) {
        var actor = policy.require(authorization.lockCurrent(requested), "sn:order:sync", true);
        long revision = control.lock();
        var attempt = attempts.selectById(id);
        if (attempt == null
                || attempt.getActorId() != actor.userId()
                || !"FETCHING".equals(attempt.getState())) throw SnPolicy.missing();
        long expected =
                attempt.getFactoryVersion() == null
                        ? attempt.getBaseVersion()
                        : attempt.getFactoryVersion();
        if (expected != revision) throw new BusinessException(SNAPSHOT_CHANGED);
        if ("scheduled".equals(attempt.getMode())) {
            var schedule = schedules.lock();
            if (!schedule.enabled()
                    || !id.equals(schedule.lastRunId())
                    || !"FETCHING".equals(schedule.lastState())
                    || schedule.leaseUntil() == null
                    || !schedule.leaseUntil().isAfter(schedules.now()))
                throw new BusinessException(SYNC_EXECUTION_EXPIRED);
        }
        return new Locked(actor, attempt, revision);
    }

    /** 仅在持有上述事务锁时有效，不能缓存后跨事务复用。 */
    public record Locked(Actor actor, OrderSyncEntity attempt, long revision) {}
}
