package com.adtek.sn.service.impl;

import com.adtek.framework.lock.NamedLockService;
import com.adtek.framework.task.RecurringTask;
import com.adtek.sn.domain.BillStatEntity;
import com.adtek.sn.mapper.BillStatMapper;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.PiBillVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 单据汇总（原系统 app/services/bill_stats.py）：每个（PI，单据，号上的生产组织）一行枚数，替代对整个号池的 GROUP BY。
 *
 * <p>「标脏 + 重算」：号的挂靠或状态一变，改号的代码调 touch*，把所在（PI，单据）记进 sn_bill_stat_dirty（同一事务内合并，提交前按主键顺序 一次写入，ver 加
 * 1，随事务回滚）。refresh 按单据重算：读标记 ver → 新开事务按单据重算并整行替换 → 只删 ver 未变的标记；重算全局串行 （命名锁）。列表查询前先 refresh（最多 3
 * 秒），后台每 2 秒 refresh 一次；每日维护抽查对账。
 */
@Service
public class BillStatsServiceImpl implements RecurringTask {
    private static final Logger log = LoggerFactory.getLogger(BillStatsServiceImpl.class);
    public static final double REFRESH_BUDGET_SECONDS = 3.0;
    public static final int REFRESH_WAIT_SECONDS = 3;
    private static final int BATCH = 200;
    private static final int FLUSH_CHUNK = 500;
    private static final Object PENDING_KEY = new Object();

    private final BillStatMapper mapper;
    private final NamedLockService locks;
    private final Tx tx;

    public BillStatsServiceImpl(BillStatMapper mapper, NamedLockService locks, Tx tx) {
        this.mapper = mapper;
        this.locks = locks;
        this.tx = tx;
    }

    // ================================================================== 标脏

    public void touch(String pi, String bill) {
        touchMany(List.of(new PiBillVO(pi, bill)));
    }

    /** 把这些（PI，单据）标为待重算；在事务里时合并到提交前一次写入，否则马上写。 */
    @SuppressWarnings("unchecked")
    public void touchMany(Collection<PiBillVO> pairs) {
        Set<String> keys = new HashSet<>();
        for (PiBillVO p : pairs) {
            if (p.piNo != null && p.billNo != null) {
                keys.add(p.piNo + "\u0000" + p.billNo);
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            flushKeys(keys);
            return;
        }
        Set<String> pending =
                (Set<String>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (pending == null) {
            Set<String> created = new HashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING_KEY, created);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            BillStatsServiceImpl.this.flushKeys(created);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                        }

                        @Override
                        public void suspend() {
                            TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                        }

                        @Override
                        public void resume() {
                            TransactionSynchronizationManager.bindResource(PENDING_KEY, created);
                        }
                    });
            pending = created;
        }
        pending.addAll(keys);
    }

    /** 按号池 id 标脏（号还在号池里时调用：删号前、改挂靠前后各一次）。 */
    public void touchIds(Collection<Long> ids) {
        List<Long> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += 1000) {
            touchMany(mapper.pairsOfItems(all.subList(i, Math.min(all.size(), i + 1000))));
        }
    }

    public void touchBatch(String batchNo) {
        if (batchNo != null && !batchNo.isEmpty()) {
            touchMany(mapper.pairsOfBatch(batchNo));
        }
    }

    public void touchBatchItems(long batchId) {
        touchMany(mapper.pairsOfBatchItems(batchId));
    }

    public void touchBill(String billNo) {
        if (billNo != null && !billNo.isEmpty()) {
            touchMany(mapper.pisOfBill(billNo).stream().map(p -> new PiBillVO(p, billNo)).toList());
        }
    }

    public void touchFactory(String factoryCode, String status) {
        touchMany(mapper.pairsOfFactory(factoryCode, status));
    }

    private void flushKeys(Set<String> keys) {
        List<PiBillVO> rows =
                new TreeSet<>(keys)
                        .stream()
                                .map(
                                        k -> {
                                            int sep = k.indexOf('\u0000');
                                            return new PiBillVO(
                                                    k.substring(0, sep), k.substring(sep + 1));
                                        })
                                .toList();
        var now = Util.now();
        for (int i = 0; i < rows.size(); i += FLUSH_CHUNK) {
            mapper.markDirty(rows.subList(i, Math.min(rows.size(), i + FLUSH_CHUNK)), now);
        }
    }

    // ================================================================== 重算

    private void recompute(String pi, String bill, Long ver) {
        tx.execIsolated(
                () -> {
                    List<BillStatEntity> rows = mapper.computeBill(pi, bill);
                    mapper.deleteBill(pi, bill);
                    if (!rows.isEmpty()) {
                        rows.forEach(
                                r -> {
                                    r.piNo = pi;
                                    r.billNo = bill;
                                });
                        mapper.insertStats(rows, Util.now());
                    }
                    if (ver != null) {
                        mapper.deleteDirty(pi, bill, ver);
                    }
                });
    }

    /** 处理标记，返回重算的单据数；等不到锁返回 0。不能在事务里调用。 */
    public int refresh(Double budgetSeconds, int wait) {
        if (Tx.inTx() || !mapper.anyDirty()) {
            return 0;
        }
        Optional<NamedLockService.Held> held = locks.tryAcquire("stat", List.of("bill"), wait);
        if (held.isEmpty()) {
            return 0;
        }
        int done = 0;
        long deadline =
                budgetSeconds == null
                        ? Long.MAX_VALUE
                        : System.nanoTime() + (long) (budgetSeconds * 1_000_000_000L);
        try {
            while (true) {
                List<PiBillVO> rows = mapper.dirty(BATCH);
                if (rows.isEmpty()) {
                    break;
                }
                for (PiBillVO r : rows) {
                    recompute(r.piNo, r.billNo, r.ver);
                    done++;
                    if (System.nanoTime() > deadline) {
                        return done;
                    }
                }
                if (rows.size() < BATCH) {
                    break;
                }
            }
        } finally {
            held.get().release();
        }
        return done;
    }

    public int refresh() {
        return refresh(REFRESH_BUDGET_SECONDS, REFRESH_WAIT_SECONDS);
    }

    @Override
    public String name() {
        return "sn-bill-stat";
    }

    @Override
    public long intervalSeconds() {
        return 2;
    }

    /** 后台定时：出错只记日志。 */
    @Override
    public void run() {
        try {
            refresh(null, 0);
        } catch (RuntimeException e) {
            log.error("bill_stat_refresh_failed", e);
        }
    }

    // ================================================================== 建立、对账

    /** 整表重建：按号池全表汇总一次，返回行数。 */
    public int rebuildAll() {
        NamedLockService.Held held =
                locks.tryAcquire("stat", List.of("bill"), 600)
                        .orElseThrow(
                                () -> new IllegalStateException("bill stat refresh lock busy"));
        int n;
        try {
            n =
                    tx.isolated(
                            () -> {
                                List<BillStatEntity> rows = mapper.computeAll();
                                mapper.deleteAll();
                                var now = Util.now();
                                for (int i = 0; i < rows.size(); i += FLUSH_CHUNK) {
                                    mapper.insertStats(
                                            rows.subList(i, Math.min(rows.size(), i + FLUSH_CHUNK)),
                                            now);
                                }
                                return rows.size();
                            });
        } finally {
            held.release();
        }
        log.warn("bill_stat_rebuilt rows={}", n);
        return n;
    }

    /** 启动时：号池有号、汇总表却是空的 → 整表回填。 */
    public boolean ensureFilled() {
        if (mapper.anyStat() || !mapper.anyItem()) {
            return false;
        }
        rebuildAll();
        return true;
    }

    /** 对账（每日维护）：最近重算过的 limit 张单据各按号池重算比较，不一致的记日志并重算；返回不一致的单据数。 */
    public int reconcile(int limit, boolean missing) {
        Optional<NamedLockService.Held> held = locks.tryAcquire("stat", List.of("bill"), 60);
        if (held.isEmpty()) {
            return 0;
        }
        List<PiBillVO> bad = new ArrayList<>();
        try {
            List<PiBillVO> pairs = mapper.recentPairs(limit);
            if (missing) {
                Set<String> have =
                        mapper.allStatPairs().stream()
                                .map(p -> p.piNo + "\u0000" + p.billNo)
                                .collect(Collectors.toSet());
                Set<String> dirty =
                        mapper.allDirtyPairs().stream()
                                .map(p -> p.piNo + "\u0000" + p.billNo)
                                .collect(Collectors.toSet());
                for (PiBillVO r : mapper.allItemPairs()) {
                    String key = r.piNo + "\u0000" + r.billNo;
                    if (!have.contains(key) && !dirty.contains(key)) {
                        bad.add(r);
                        log.warn("bill_stat_mismatch pi={} bill={} missing=true", r.piNo, r.billNo);
                    }
                }
            }
            for (PiBillVO p : pairs) {
                Boolean mismatch =
                        tx.isolated(
                                () -> {
                                    List<String> fresh =
                                            sorted(mapper.computeBill(p.piNo, p.billNo));
                                    if (mapper.dirtyFor(p.piNo, p.billNo)) {
                                        return false;
                                    }
                                    List<String> have = sorted(mapper.storedBill(p.piNo, p.billNo));
                                    return !have.equals(fresh);
                                });
                if (Boolean.TRUE.equals(mismatch)) {
                    bad.add(p);
                    log.warn("bill_stat_mismatch pi={} bill={}", p.piNo, p.billNo);
                }
            }
            for (PiBillVO p : bad) {
                recompute(p.piNo, p.billNo, null);
            }
        } finally {
            held.get().release();
        }
        return bad.size();
    }

    private static List<String> sorted(List<BillStatEntity> rows) {
        return rows.stream().map(BillStatEntity::compareKey).sorted().toList();
    }
}
