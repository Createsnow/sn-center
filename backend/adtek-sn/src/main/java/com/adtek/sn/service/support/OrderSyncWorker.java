package com.adtek.sn.service.support;

import com.adtek.sn.service.OrderScheduleService;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.*;

/** 每30秒扫描的有界单线程；MySQL负责跨实例抢占，默认设置关闭时不连接ERP。 */
@Component
public class OrderSyncWorker implements SmartLifecycle {
    private final OrderScheduleService schedules;
    private final boolean enabled;
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public OrderSyncWorker(
            OrderScheduleService schedules,
            @Value("${adtek.sn.order-sync-worker.enabled:true}") boolean enabled) {
        this.schedules = schedules;
        this.enabled = enabled;
    }

    @Override
    public synchronized void start() {
        if (!enabled || running) return;
        running = true;
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("adtek-order-sync").factory());
        executor.scheduleWithFixedDelay(this::tick, 0, 30, TimeUnit.SECONDS);
    }

    private void tick() {
        if (!running) return;
        try (var trace = MDC.putCloseable("traceId", UUID.randomUUID().toString())) {
            schedules.runDue();
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(getClass())
                    .warn(
                            "Scheduled order sync unavailable exceptionType={}",
                            error.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException error) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }
}
