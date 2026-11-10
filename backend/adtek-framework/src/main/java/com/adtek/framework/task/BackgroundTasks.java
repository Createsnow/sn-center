package com.adtek.framework.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

/**
 * 后台任务执行：请求内提交的耗时工作（如大批量生成）在共享线程池执行，日志上下文（traceId）随任务传递。
 *
 * <p>任务的持久状态（进度、终态、中断回收）由业务表负责；进程退出时未完成的任务由业务的回收任务处理。
 */
public class BackgroundTasks {
    private static final Logger log = LoggerFactory.getLogger(BackgroundTasks.class);
    private final ThreadPoolTaskExecutor executor;

    BackgroundTasks(ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    /** 提交任务；线程池已满时抛出 {@link TaskRejectedException}，调用方负责回滚自己的状态。 */
    public void submit(String name, Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        executor.execute(
                () -> {
                    if (context != null) {
                        MDC.setContextMap(context);
                    }
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        log.error("Background task {} failed", name, e);
                    } finally {
                        MDC.clear();
                    }
                });
    }
}
