package com.adtek.framework.task;

/**
 * 业务模块的周期任务：实现本接口并注册为 Bean，由 framework 调度器在 Web 进程中执行（migrate 等非 Web 进程不执行）。
 *
 * <p>多实例时各实例都会触发；任务自身用命名锁与持久状态防并发、去重（见 {@code NamedLockService}）。任务抛出的异常只记日志，不影响下次执行。
 */
public interface RecurringTask {
    /** 任务名（日志、线程名）。 */
    String name();

    /**
     * Spring 6 段 cron（秒 分 时 日 月 周），按 {@link #zone()} 解释；返回 null 时按 {@link #intervalSeconds()}
     * 固定间隔执行。
     */
    default String cron() {
        return null;
    }

    /** 固定间隔（秒），首次在启动后一个间隔执行。 */
    default long intervalSeconds() {
        return 60;
    }

    /** cron 的时区（IANA）。 */
    default String zone() {
        return "Asia/Shanghai";
    }

    /** 是否启用（读取配置开关）。 */
    default boolean enabled() {
        return true;
    }

    void run();
}
