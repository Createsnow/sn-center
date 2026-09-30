package com.adtek.framework.task;

import com.adtek.framework.task.service.TaskExecutionService;

import org.slf4j.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.*;

/** 单调度线程不积累无界任务队列；启动即扫描，关闭先停止领取，等在途事务后再释放依赖。 */
@Component
@EnableConfigurationProperties(TaskSettings.class)
public class TaskWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(TaskWorker.class);
    private final TaskExecutionService execution;
    private final TaskSettings settings;
    private final String owner = UUID.randomUUID().toString();
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public TaskWorker(TaskExecutionService execution, TaskSettings settings) {
        this.execution = execution;
        this.settings = settings;
    }

    @Override
    public synchronized void start() {
        if (!settings.enabled() || running) {
            return;
        }
        running = true;
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("adtek-durable-worker").factory());
        executor.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    private void tick() {
        if (!running) {
            return;
        }
        try (var trace = MDC.putCloseable("traceId", UUID.randomUUID().toString())) {
            try {
                var task = execution.claim(owner);
                if (task == null) {
                    return;
                }
                try {
                    execution.execute(task);
                } catch (Exception failure) {
                    execution.failed(task, failure);
                }
            } catch (Exception failure) {
                LOG.warn(
                        "Durable task poll unavailable exceptionType={}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
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
