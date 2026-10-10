package com.adtek.sn.service.impl;

import com.adtek.sn.constant.ErrorCodeConstants;
import com.adtek.sn.domain.OrderScheduleEntity;
import com.adtek.sn.domain.OrderSyncEntity;
import com.adtek.sn.mapper.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.UUID;

/** 与网络请求分离的短事务；同快照锁→设置锁顺序也被保存和发布使用。 */
@Service
public class OrderScheduleControl {
    private final SnControlMapper control;
    private final OrderScheduleMapper schedules;
    private final OrderSyncMapper attempts;

    public OrderScheduleControl(
            SnControlMapper control, OrderScheduleMapper schedules, OrderSyncMapper attempts) {
        this.control = control;
        this.schedules = schedules;
        this.attempts = attempts;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public OrderSyncEntity claim() {
        long base = control.lock();
        var row = schedules.lock();
        var now = schedules.now();
        if ("FETCHING".equals(row.lastState())) {
            if (row.leaseUntil().isAfter(now)) return null;
            invalidate(row);
        }
        if (!row.enabled() || row.nextRunAt() == null || row.nextRunAt().isAfter(now)) return null;
        var attempt = new OrderSyncEntity();
        attempt.setId(UUID.randomUUID().toString());
        attempt.setActorId(row.ownerId());
        attempt.setMode("scheduled");
        attempt.setBillNo("");
        attempt.setState("FETCHING");
        attempt.setBaseVersion(base);
        attempt.setRowsCount(0L);
        attempt.setBillsCount(0L);
        attempt.setOrgSyncFailed(false);
        attempt.setStartedAt(now);
        attempts.insert(attempt);
        schedules.claim(attempt.getId());
        return attempt;
    }

    // 调用方已经持有快照/设置锁；失败状态与取消执行权原子提交。
    void invalidate(OrderScheduleEntity row) {
        if (!"FETCHING".equals(row.lastState())) return;
        var attempt = attempts.selectById(row.lastRunId());
        if (attempt != null && "FETCHING".equals(attempt.getState())) {
            attempt.setState("FAILED");
            attempt.setErrorCode(ErrorCodeConstants.SYNC_EXECUTION_EXPIRED.code());
            attempt.setFinishedAt(schedules.now());
            attempts.updateById(attempt);
        }
        schedules.failed(row.lastRunId(), ErrorCodeConstants.SYNC_EXECUTION_EXPIRED.code(), "");
    }
}
