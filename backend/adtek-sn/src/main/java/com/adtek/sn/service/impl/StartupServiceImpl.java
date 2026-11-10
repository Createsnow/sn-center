package com.adtek.sn.service.impl;

import com.adtek.framework.lock.NamedLockService;
import com.adtek.sn.support.SnSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 启动初始化（原系统 main.startup）：在跨实例命名锁 startup 内依次补齐单据汇总、回收中断的生成任务、建 / 重置系统管理员、建月分区（及每日维护）。
 * 先于周期任务执行（周期任务在应用就绪后才开始）；表结构由 migrate 任务负责，这里不做 DDL 建表。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StartupServiceImpl implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupServiceImpl.class);
    public static final int STARTUP_LOCK_SECONDS = 180;

    private final NamedLockService locks;
    private final BillStatsServiceImpl billStats;
    private final GenerateServiceImpl generate;
    private final UserServiceImpl users;
    private final MaintainServiceImpl maintain;
    private final SnSettings settings;

    public StartupServiceImpl(
            NamedLockService locks,
            BillStatsServiceImpl billStats,
            GenerateServiceImpl generate,
            UserServiceImpl users,
            MaintainServiceImpl maintain,
            SnSettings settings) {
        this.locks = locks;
        this.billStats = billStats;
        this.generate = generate;
        this.users = users;
        this.maintain = maintain;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<NamedLockService.Held> held =
                locks.tryAcquire("startup", List.of("init"), STARTUP_LOCK_SECONDS);
        if (held.isEmpty()) {
            throw new IllegalStateException("等待其他进程完成启动初始化超过 " + STARTUP_LOCK_SECONDS + " 秒");
        }
        try (NamedLockService.Held h = held.get()) {
            billStats.ensureFilled();
            generate.recoverInterrupted();
            initAdmin();
            maintain.maintain(settings.maintainEnabled());
        }
        log.info("sn_center_ready retention_days={}", settings.auditRetentionDays());
    }

    /** 按环境变量建系统管理员（空库）或重置它（SN_RESET_ADMIN=true）；生产环境不允许弱口令。 */
    void initAdmin() {
        boolean reset = settings.getBool("SN_RESET_ADMIN", false);
        if (!reset && !users.needsBootstrap()) {
            return;
        }
        String problem = settings.adminPasswordProblem();
        if (problem != null) {
            if (settings.production()) {
                throw new IllegalStateException(problem + "：生产环境必须设置 SN_INIT_ADMIN_PASSWORD");
            }
            log.warn("weak_admin_password: {} (ENVIRONMENT=production 时将拒绝启动)", problem);
        }
        String emp = settings.initAdminEmpNo();
        if (reset) {
            users.resetAdmin(settings.initAdminPassword());
            log.warn("admin_reset emp_no={}; remove SN_RESET_ADMIN now", emp);
        } else if (users.bootstrapAdmin(settings.initAdminPassword())) {
            log.warn("bootstrap_admin emp_no={}", emp);
        }
    }
}
