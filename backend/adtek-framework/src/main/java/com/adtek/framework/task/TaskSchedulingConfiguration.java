package com.adtek.framework.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/** 调度与后台执行设施：周期任务（{@link RecurringTask}）调度器与后台任务执行器（{@link BackgroundTasks}），只在 Web 进程中启用。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TaskSchedulingConfiguration {
    private static final Logger log = LoggerFactory.getLogger(TaskSchedulingConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler adtekTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(6);
        scheduler.setThreadNamePrefix("adtek-cron-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor adtekBackgroundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("adtek-bg-");
        // 停机时等后台任务（如生成）尽量跑完
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean
    BackgroundTasks backgroundTasks(ThreadPoolTaskExecutor adtekBackgroundExecutor) {
        return new BackgroundTasks(adtekBackgroundExecutor);
    }

    @Bean
    RecurringTaskRunner recurringTaskRunner(
            ThreadPoolTaskScheduler adtekTaskScheduler, ObjectProvider<RecurringTask> tasks) {
        return new RecurringTaskRunner(adtekTaskScheduler, tasks);
    }

    /** 应用就绪后登记全部周期任务。 */
    public static final class RecurringTaskRunner implements DisposableBean {
        private final ThreadPoolTaskScheduler scheduler;
        private final ObjectProvider<RecurringTask> tasks;
        private final List<ScheduledFuture<?>> futures = new ArrayList<>();

        RecurringTaskRunner(
                ThreadPoolTaskScheduler scheduler, ObjectProvider<RecurringTask> tasks) {
            this.scheduler = scheduler;
            this.tasks = tasks;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void start() {
            tasks.orderedStream()
                    .forEach(
                            task -> {
                                if (!task.enabled()) {
                                    log.info("Recurring task {} disabled", task.name());
                                    return;
                                }
                                Runnable body = () -> runSafely(task);
                                String cron = task.cron();
                                if (cron != null && CronExpression.isValidExpression(cron)) {
                                    futures.add(
                                            scheduler.schedule(
                                                    body,
                                                    new CronTrigger(cron, ZoneId.of(task.zone()))));
                                } else {
                                    if (cron != null) {
                                        log.warn("Bad cron {} for {}", cron, task.name());
                                    }
                                    Duration every =
                                            Duration.ofSeconds(Math.max(1, task.intervalSeconds()));
                                    futures.add(
                                            scheduler.scheduleWithFixedDelay(
                                                    body, Instant.now().plus(every), every));
                                }
                            });
        }

        private static void runSafely(RecurringTask task) {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Recurring task {} failed", task.name(), e);
            }
        }

        @Override
        public void destroy() {
            futures.forEach(f -> f.cancel(false));
        }
    }
}
