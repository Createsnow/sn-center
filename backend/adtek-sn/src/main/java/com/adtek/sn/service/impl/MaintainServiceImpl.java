package com.adtek.sn.service.impl;

import com.adtek.framework.lock.NamedLockService;
import com.adtek.framework.mybatis.MonthlyPartitionMaintainer;
import com.adtek.framework.task.RecurringTask;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.mapper.MaintainMapper;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 按月分区与每日维护（原系统 partitions.py）：sn_audit 提前建好未来 N 个月分区；保存期之外的整月分区先拷入 sn_audit_archive 再 DROP；同步生产组织、
 * 每日交付自检、单据汇总对账；归档表超过总保存上限的分批删除。多实例用命名锁 sn_partition 防同时执行，按 sn_seq 里的上次完成时间去重。
 */
@Service
public class MaintainServiceImpl implements RecurringTask {
    private static final Logger log = LoggerFactory.getLogger(MaintainServiceImpl.class);
    public static final Duration DAILY_MIN_GAP = Duration.ofHours(1);
    public static final String DAILY_MARK = "#daily_maintain";
    public static final int PURGE_BATCH = 10_000;
    private static final DateTimeFormatter BOUND =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MaintainMapper mapper;
    private final MonthlyPartitionMaintainer partitions;
    private final NamedLockService locks;
    private final FactoryServiceImpl factories;
    private final DeliveryCheckServiceImpl selfCheck;
    private final BillStatsServiceImpl billStats;
    private final AuditServiceImpl audit;
    private final SnSettings settings;
    private final Tx tx;

    public MaintainServiceImpl(
            MaintainMapper mapper,
            MonthlyPartitionMaintainer partitions,
            NamedLockService locks,
            FactoryServiceImpl factories,
            DeliveryCheckServiceImpl selfCheck,
            BillStatsServiceImpl billStats,
            AuditServiceImpl audit,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.partitions = partitions;
        this.locks = locks;
        this.factories = factories;
        this.selfCheck = selfCheck;
        this.billStats = billStats;
        this.audit = audit;
        this.settings = settings;
        this.tx = tx;
    }

    @Override
    public String name() {
        return "sn-daily-maintain";
    }

    @Override
    public String cron() {
        return settings.partitionCron();
    }

    @Override
    public String zone() {
        return settings.zone().getId();
    }

    @Override
    public boolean enabled() {
        return settings.maintainEnabled();
    }

    @Override
    public void run() {
        maintain(true);
    }

    /** 建月分区；daily = true 时再做每日维护（DAILY_MIN_GAP 内已有实例做完则跳过）。 */
    public void maintain(boolean daily) {
        Optional<NamedLockService.Held> held = locks.tryAcquire("partition", List.of("sn"), 0);
        if (held.isEmpty()) {
            log.info("partition_maintain_skipped: other instance holds lock");
            return;
        }
        try (NamedLockService.Held h = held.get()) {
            ensureAudit();
            if (!daily) {
                return;
            }
            Long last = mapper.lastDaily(DAILY_MARK);
            long nowSec = System.currentTimeMillis() / 1000;
            if (last != null && nowSec - last < DAILY_MIN_GAP.toSeconds()) {
                log.info("daily_maintain_skipped: done {}s ago", nowSec - last);
                return;
            }
            archive(settings.auditRetentionDays());
            purgeArchive(settings.auditArchiveRetentionDays());
            mapper.purgePreviews();
            mapper.purgeRateCounters();
            dailyJobs();
            mapper.markDaily(DAILY_MARK, System.currentTimeMillis() / 1000);
        }
    }

    private void dailyJobs() {
        try {
            factories.syncFromK3(CurrentUser.system());
        } catch (RuntimeException e) {
            log.warn("daily_factory_sync_failed: {}", e.toString());
        }
        try {
            selfCheck.run();
        } catch (RuntimeException e) {
            log.error("daily_self_check_failed", e);
        }
        try {
            int bad = billStats.reconcile(2000, false);
            log.info("bill_stat_reconciled mismatched={}", bad);
        } catch (RuntimeException e) {
            log.error("bill_stat_reconcile_failed", e);
        }
    }

    void ensureAudit() {
        String highest = "";
        for (MonthlyPartitionMaintainer.Partition p : partitions.partitions("sn_audit")) {
            if (p.bound() != null && p.bound().compareTo(highest) > 0) {
                highest = p.bound();
            }
        }
        LocalDate first = Util.now().toLocalDate().withDayOfMonth(1);
        List<MonthlyPartitionMaintainer.MonthDef> defs = new ArrayList<>();
        for (int i = 0; i <= settings.partitionAheadMonths(); i++) {
            LocalDate m = first.plusMonths(i);
            String bound = BOUND.format(m.plusMonths(1).atStartOfDay());
            if (bound.compareTo(highest) > 0) {
                defs.add(
                        new MonthlyPartitionMaintainer.MonthDef(
                                String.format("p%04d%02d", m.getYear(), m.getMonthValue()), bound));
            }
        }
        partitions.addMonths("sn_audit", defs);
    }

    /** 上界不晚于（今天 − 保存天数）的整月分区归档后删除；至少留一个有上界的分区。 */
    int archive(int days) {
        if (days <= 0) {
            return 0;
        }
        String cutoff = BOUND.format(Util.now().toLocalDate().minusDays(days).atStartOfDay());
        List<MonthlyPartitionMaintainer.Partition> parts = partitions.partitions("sn_audit");
        long bounded = parts.stream().filter(p -> p.bound() != null).count();
        int archived = 0;
        String lower = null;
        for (MonthlyPartitionMaintainer.Partition p : parts) {
            String bound = p.bound();
            if (bound == null || bound.compareTo(cutoff) > 0 || bounded <= 1) {
                lower = bound;
                continue;
            }
            LocalDateTime lo = lower == null ? null : LocalDateTime.parse(lower, BOUND);
            LocalDateTime hi = LocalDateTime.parse(bound, BOUND);
            int moved = tx.run(() -> mapper.archiveRange(lo, hi));
            partitions.dropPartition("sn_audit", p.name());
            bounded--;
            archived++;
            lower = bound;
            log.info("audit_archived partition={} rows={}", p.name(), moved);
            audit.recordIsolated(
                    CurrentUser.system(),
                    AuditEntryDTO.of(AuditAction.AUDIT_ARCHIVE)
                            .count(moved)
                            .info(
                                    "partition="
                                            + p.name()
                                            + " before="
                                            + bound
                                            + " at="
                                            + LocalDateTime.now()));
        }
        return archived;
    }

    /** 删除发生时间早于（今天 − 保存天数）的归档痕迹；总保存期不短于在线期。 */
    public int purgeArchive(int days) {
        int online = settings.auditRetentionDays();
        if (days <= 0 || online <= 0) {
            return 0;
        }
        int keep = Math.max(days, online);
        LocalDateTime cutoff = Util.now().toLocalDate().minusDays(keep).atStartOfDay();
        int total = 0;
        while (true) {
            int n = tx.isolated(() -> mapper.purgeArchive(cutoff, PURGE_BATCH));
            total += n;
            if (n < PURGE_BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("audit_archive_purged rows={} before={}", total, cutoff);
            audit.recordIsolated(
                    CurrentUser.system(),
                    AuditEntryDTO.of(AuditAction.AUDIT_ARCHIVE_PURGE)
                            .count(total)
                            .info("before=" + BOUND.format(cutoff) + " keep_days=" + keep));
        }
        return total;
    }
}
