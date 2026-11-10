package com.adtek.sn.service.impl;

import static com.adtek.sn.service.impl.BatchServiceImpl.FAILED;
import static com.adtek.sn.service.impl.BatchServiceImpl.SRC_PULL;
import static com.adtek.sn.service.impl.BatchServiceImpl.SRC_PUSH;
import static com.adtek.sn.service.impl.BatchServiceImpl.SUCCESS;
import static com.adtek.sn.service.impl.BatchServiceImpl.UNCONFIRMED;
import static com.adtek.sn.service.impl.BatchServiceImpl.VOIDED;
import static com.adtek.sn.service.impl.BatchServiceImpl.XO_KEY;

import com.adtek.framework.integration.MesPushPort;
import com.adtek.framework.task.BackgroundTasks;
import com.adtek.framework.task.RecurringTask;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.domain.FactoryEntity;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.mapper.DeliveryMapper;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.DbErrors;
import com.adtek.sn.support.MesWire;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SecretBox;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.PushRemainingVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 推送任务（原系统 delivery/push.py）：同组织 + 同一 PI 一个任务（转出批次用 #XO）；批次串行执行，一批确认成功再发下一批。
 *
 * <ul>
 *   <li>MES 应答「已收到」后批次进入等回执（WAIT_RECEIPT）；回执 OK 确认成功并推下一批，NG 停止并提示原因；
 *   <li>等回执超时按原批次号、原内容重推（SN_PUSH_RECEIPT_WAIT_MINUTES），用尽即停止（EXHAUSTED）；
 *   <li>没送达用原批次号、原内容自动重试（SN_PUSH_RETRY_INTERVALS），用尽即停止；
 *   <li>同组织同时运行的任务数不超过推送并发上限，同一 PI 同一时刻只运行一个任务；租约过期的任务由其他实例接手；
 *   <li>一个实例同时执行的任务不超过 {@link #PUSH_WORKERS}；数据库锁冲突转为等待，{@link #TRANSIENT_RETRY_SECONDS} 秒后接着推。
 * </ul>
 *
 * 调度：周期任务按 SN_PUSH_POLL_SECONDS 轮询，{@link #poke()} 立即唤醒一次；任务在后台执行器上运行。
 */
@Service
public class PushServiceImpl implements RecurringTask {
    private static final Logger log = LoggerFactory.getLogger(PushServiceImpl.class);

    public static final int PUSH_WORKERS = 8;
    public static final int LEASE_SECONDS = 300;
    public static final int HEARTBEAT_SECONDS = 60;
    public static final int TRANSIENT_RETRY_SECONDS = 10;
    public static final int TRANSIENT_MAX = 5;
    public static final String CLAIM_MUTEX = "#push_claim";
    public static final String RUNNER = runnerName();

    private final DeliveryMapper mapper;
    private final BatchServiceImpl batches;
    private final ReceiptServiceImpl receipts;
    private final FactoryServiceImpl factories;
    private final AuditServiceImpl audit;
    private final MesPushPort mes;
    private final SecretBox secretBox;
    private final SnSettings settings;
    private final BackgroundTasks background;
    private final Tx tx;

    private final Set<String> runningHere = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inflight = new AtomicInteger();
    private final Map<String, Integer> transientCounts = new ConcurrentHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();

    public PushServiceImpl(
            DeliveryMapper mapper,
            BatchServiceImpl batches,
            @Lazy ReceiptServiceImpl receipts,
            FactoryServiceImpl factories,
            AuditServiceImpl audit,
            MesPushPort mes,
            SecretBox secretBox,
            SnSettings settings,
            BackgroundTasks background,
            Tx tx) {
        this.mapper = mapper;
        this.batches = batches;
        this.receipts = receipts;
        this.factories = factories;
        this.audit = audit;
        this.mes = mes;
        this.secretBox = secretBox;
        this.settings = settings;
        this.background = background;
        this.tx = tx;
    }

    private static String runnerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "localhost";
        }
        return host + ":" + ManagementFactory.getRuntimeMXBean().getPid();
    }

    // ---------------------------------------------------------------- 调度
    @Override
    public String name() {
        return "sn-push-scheduler";
    }

    @Override
    public long intervalSeconds() {
        return Math.max(1, settings.pushPollSeconds());
    }

    @Override
    public boolean enabled() {
        return settings.pushEnabled();
    }

    @Override
    public void run() {
        runPending(false);
    }

    /** 立即唤醒调度一次（合并并发的唤醒）。 */
    public void poke() {
        if (!settings.pushEnabled()) {
            return;
        }
        Tx.afterTx(this::schedule);
    }

    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) {
            again.set(true);
            return;
        }
        try {
            background.submit(
                    "sn-push-poke",
                    () -> {
                        try {
                            do {
                                again.set(false);
                                runPending(false);
                            } while (again.get());
                        } catch (RuntimeException e) {
                            log.error("push_scheduler_failed", e);
                        } finally {
                            scheduled.set(false);
                        }
                    });
        } catch (RuntimeException e) {
            scheduled.set(false);
            log.warn("push_poke_rejected: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- 启动任务
    /** 启动（或排队）推送任务：不存在 / 已结束 → 排队；运行中 → 结束后再排一次。死锁时自动重做（不在外层事务里时）。 */
    public void startTask(CurrentUser cu, String org, String key, boolean resend) {
        DbErrors.retryDeadlock(
                () -> {
                    startTaskTx(cu, org, key, resend);
                    return null;
                },
                DbErrors.DEADLOCK_ATTEMPTS,
                false);
        poke();
    }

    public void startTask(CurrentUser cu, String org, String key) {
        startTask(cu, org, key, false);
    }

    private void startTaskTx(CurrentUser cu, String org, String key, boolean resend) {
        LocalDateTime now = Util.now();
        tx.exec(
                () -> {
                    if (!mapper.taskExists(org, key)
                            && mapper.taskInsertIgnore(org, key, resend, cu.empNo(), now) > 0) {
                        return;
                    }
                    Map<String, Object> t = mapper.taskLock(org, key);
                    if (t == null) {
                        mapper.taskInsert(org, key, resend, cu.empNo(), now);
                    } else if ("RUNNING".equals(R.s(t, "state"))) {
                        mapper.taskRequeue(org, key, resend, now);
                    } else if ("WAITING".equals(R.s(t, "state")) && !resend) {
                        // 等回执、等重试中：到时自动接着推
                    } else {
                        mapper.taskQueue(org, key, resend, cu.empNo(), now);
                    }
                });
    }

    /** 继续推送：重置未确认、失败批次的自动重试与等回执次数，按原批次号、原内容先重推，再认领后续的号。 */
    public PushRemainingVO resume(CurrentUser cu, String orgIn, String keyIn) {
        String org = factories.mustGet(orgIn).factoryCode;
        cu.checkWrite(org);
        String key = Util.trim(keyIn);
        if (key.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        PushRemainingVO remaining =
                tx.run(
                        () -> {
                            int n = mapper.resetForResume(org, key, Util.now());
                            PushRemainingVO rem = remainingPushes(org, key);
                            if (n == 0 && rem.unbatched == 0) {
                                throw Biz.err(SnErrorCode.PUSH_NOTHING, "factory", org, "pi", key);
                            }
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.SN_PUSH_RESUME)
                                            .factory(org)
                                            .pi(XO_KEY.equals(key) ? null : key)
                                            .count(rem.unbatched)
                                            .info(
                                                    "key="
                                                            + key
                                                            + " batches="
                                                            + n
                                                            + " remaining="
                                                            + rem.pushes));
                            return rem;
                        });
        startTask(cu, org, key, true);
        remaining.factoryCode = org;
        remaining.key = key;
        return remaining;
    }

    /** 「剩余 N 次」：未入批且未暂停的号按类型分批、按单批枚数折算的次数 + 未确认或失败的批次数。 */
    public PushRemainingVO remainingPushes(String org, String key) {
        int size = batches.batchSize(org);
        List<BatchServiceImpl.PoolGroup> groups =
                XO_KEY.equals(key) ? List.of() : batches.poolGroups(org, key, null);
        long open = mapper.openBatchCount(org, key);
        long pushes = open;
        long unbatched = 0;
        for (BatchServiceImpl.PoolGroup g : groups) {
            pushes += (g.n() + size - 1) / size;
            unbatched += g.n();
        }
        PushRemainingVO v = new PushRemainingVO();
        v.unbatched = unbatched;
        v.batches = open;
        v.pushes = pushes;
        return v;
    }

    public Map<String, Object> taskState(String org, String key) {
        Map<String, Object> t = mapper.task(org, key);
        if (t == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : t.entrySet()) {
            out.put(com.adtek.sn.support.Camel.key(e.getKey()), e.getValue());
        }
        return out;
    }

    /** 单据解除暂停 / 号回到未入批后：涉及的（组织，PI）有可推送的号时启动推送。 */
    public void kickBill(String billNo) {
        for (Map<String, Object> r : mapper.kickTargets(billNo)) {
            if (factories.isMes(R.s(r, "factory_code"))) {
                startTask(CurrentUser.system(), R.s(r, "factory_code"), R.s(r, "pi_no"));
            }
        }
    }

    // ---------------------------------------------------------------- 认领与执行
    private Map<String, Object> claimTask() {
        LocalDateTime now = Util.now();
        List<Map<String, Object>> rows = mapper.runnableTasks(now);
        if (!rows.isEmpty() && !mapper.seqExists(CLAIM_MUTEX)) {
            mapper.seqInsert(CLAIM_MUTEX);
        }
        for (Map<String, Object> t : rows) {
            String org = R.s(t, "factory_code");
            String key = R.s(t, "task_key");
            int n =
                    tx.run(
                            () -> {
                                mapper.seqLock(CLAIM_MUTEX);
                                if (mapper.busyOrg(org, now) >= R.l(t, "push_concurrency")) {
                                    return 0;
                                }
                                if (!XO_KEY.equals(key) && mapper.keyRunning(key, now)) {
                                    return 0;
                                }
                                return mapper.claimTask(
                                        org,
                                        key,
                                        R.s(t, "state"),
                                        R.t(t, "updated_at"),
                                        now.plusSeconds(LEASE_SECONDS),
                                        RUNNER,
                                        now);
                            });
            if (n == 1) {
                return t;
            }
        }
        return null;
    }

    /** 跑完当前所有可运行的任务；返回执行的任务数。wait = false（调度）：只认领空闲推送线程数那么多个，提交到后台执行器即返回。 */
    public int runPending(boolean wait) {
        int count = 0;
        while (true) {
            if (!wait) {
                if (inflight.incrementAndGet() > PUSH_WORKERS) {
                    inflight.decrementAndGet();
                    return count;
                }
            }
            Map<String, Object> t;
            try {
                t = claimTask();
            } catch (RuntimeException e) {
                if (!wait) {
                    inflight.decrementAndGet();
                }
                throw e;
            }
            if (t == null) {
                if (!wait) {
                    inflight.decrementAndGet();
                }
                return count;
            }
            count++;
            if (wait) {
                runTask(t);
            } else {
                try {
                    background.submit(
                            "sn-push",
                            () -> {
                                try {
                                    runTask(t);
                                } finally {
                                    inflight.decrementAndGet();
                                    poke();
                                }
                            });
                } catch (RuntimeException e) {
                    // 执行器已满：任务留在运行中，租约过期后重新认领
                    inflight.decrementAndGet();
                    log.warn("push_submit_rejected: {}", e.toString());
                    return count;
                }
            }
        }
    }

    private void renew(Map<String, Object> t, String batchNo) {
        LocalDateTime now = Util.now();
        mapper.renewTask(
                R.s(t, "factory_code"),
                R.s(t, "task_key"),
                batchNo,
                now.plusSeconds(LEASE_SECONDS),
                now);
    }

    private void extendLease(Map<String, Object> t) {
        mapper.extendLease(
                R.s(t, "factory_code"),
                R.s(t, "task_key"),
                RUNNER,
                Util.now().plusSeconds(LEASE_SECONDS));
    }

    private void endTask(
            Map<String, Object> t,
            String stateIn,
            String message,
            LocalDateTime runAfter,
            String batchNo) {
        String org = R.s(t, "factory_code");
        String key = R.s(t, "task_key");
        String state =
                tx.run(
                        () -> {
                            Map<String, Object> cur = mapper.taskRequeueFlag(org, key);
                            String st = stateIn;
                            if (cur != null && R.b(cur, "requeue") && "DONE".equals(st)) {
                                st = "QUEUED";
                            }
                            mapper.endTask(
                                    org,
                                    key,
                                    st,
                                    runAfter,
                                    batchNo,
                                    Util.cut(message, 500),
                                    Util.now());
                            return st;
                        });
        if ("QUEUED".equals(state)) {
            poke();
        }
    }

    private record Next(Map<String, Object> batch, String kind, String info) {
        static Next of(Map<String, Object> b) {
            return new Next(b, null, null);
        }

        static Next waitFor(String batchNo) {
            return new Next(null, "WAIT", batchNo);
        }

        static Next stop(String why) {
            return new Next(null, "STOP", why);
        }
    }

    private static String failText(Map<String, Object> b) {
        String r = R.s(b, "fail_reason");
        return r == null || r.isEmpty() ? R.s(b, "fail_code") : r;
    }

    /** 下一个要发的批次：先未确认 / 失败的，再认领新号建批；或等待（等重试、等回执）/ 停止。 */
    private Next nextBatch(Map<String, Object> t, boolean resend) {
        String org = R.s(t, "factory_code");
        String key = R.s(t, "task_key");
        for (Map<String, Object> b : mapper.pendingBatches(org, key)) {
            String no = R.s(b, "batch_no");
            if (FAILED.equals(R.s(b, "result"))) {
                if (resend) {
                    return Next.of(b);
                }
                return Next.stop("批次 " + no + " 失败：" + failText(b));
            }
            String ps = R.s(b, "push_state");
            LocalDateTime next = R.t(b, "next_retry_at");
            if ("WAIT_RETRY".equals(ps)) {
                if (next != null && next.isAfter(Util.now())) {
                    return Next.waitFor(no);
                }
                return Next.of(b);
            }
            if ("WAIT_RECEIPT".equals(ps)) {
                if (next != null && next.isAfter(Util.now())) {
                    return Next.waitFor(no);
                }
                String why = receiptTimeout(b);
                return why != null ? Next.stop(why) : Next.of(b);
            }
            if ("EXHAUSTED".equals(ps)) {
                if (resend) {
                    return Next.of(b);
                }
                return Next.stop("批次 " + no + " 自动重试用尽仍未确认");
            }
            if (SRC_PULL.equals(R.s(b, "source")) && !resend) {
                continue;
            }
            return Next.of(b);
        }
        if (XO_KEY.equals(key) || !factories.isMes(org)) {
            return null;
        }
        Map<String, Object> b =
                batches.buildDelivery(CurrentUser.system(), org, key, SRC_PUSH, null);
        return b == null ? null : Next.of(b);
    }

    /** 等 ③ 回执超时：未用尽的改为待重推（返回 null）；用尽的记为重试用尽，返回原因。 */
    private String receiptTimeout(Map<String, Object> b) {
        List<Integer> waits = settings.pushReceiptWaits();
        CurrentUser sys = CurrentUser.system();
        return tx.run(
                () -> {
                    Map<String, Object> cur = batches.mustBatch(R.s(b, "batch_no"), true);
                    if (!UNCONFIRMED.equals(R.s(cur, "result"))
                            || !"WAIT_RECEIPT".equals(R.s(cur, "push_state"))) {
                        return null;
                    }
                    int n = R.i(cur, "receipt_timeouts");
                    int wait = waits.get(Math.min(n, waits.size() - 1));
                    String reason;
                    String out;
                    if (n < waits.size()) {
                        reason = "等待 MES 回执超时（" + wait + " 分钟），第 " + (n + 1) + " 次按原批次重推";
                        mapper.receiptTimeoutResend(R.l(cur, "id"), reason, Util.now());
                        out = null;
                    } else {
                        reason = "已重推 " + n + " 次，等待 MES 回执超时（" + wait + " 分钟）仍未收到";
                        mapper.receiptTimeoutExhausted(R.l(cur, "id"), reason, Util.now());
                        out = "批次 " + R.s(cur, "batch_no") + "：" + reason;
                    }
                    audit.record(
                            sys,
                            AuditEntryDTO.of(AuditAction.SN_PUSH)
                                    .fail(reason)
                                    .factory(R.s(cur, "factory_code"))
                                    .pi(R.s(cur, "pi_no"))
                                    .count(R.l(cur, "qty"))
                                    .batch(R.s(cur, "batch_no"))
                                    .info(
                                            "code=RECEIPT_TIMEOUT timeouts="
                                                    + n
                                                    + " next="
                                                    + (out == null ? "RESEND" : "EXHAUSTED")));
                    return out;
                });
    }

    private static String waitMessage(Map<String, Object> b) {
        if ("WAIT_RECEIPT".equals(R.s(b, "push_state"))) {
            return "批次 " + R.s(b, "batch_no") + " 已送达，等待 MES 回执";
        }
        return "批次 " + R.s(b, "batch_no") + " 等待自动重试";
    }

    void runTask(Map<String, Object> t) {
        String key = R.s(t, "factory_code") + "\u0000" + R.s(t, "task_key");
        if (!runningHere.add(key)) {
            return;
        }
        Thread beat = null;
        try {
            beat =
                    Thread.ofVirtual()
                            .name("sn-push-lease")
                            .start(
                                    () -> {
                                        while (!Thread.currentThread().isInterrupted()) {
                                            try {
                                                Thread.sleep(HEARTBEAT_SECONDS * 1000L);
                                            } catch (InterruptedException e) {
                                                return;
                                            }
                                            try {
                                                extendLease(t);
                                            } catch (RuntimeException e) {
                                                log.warn(
                                                        "push_lease_renew_failed org={} key={}",
                                                        R.s(t, "factory_code"),
                                                        R.s(t, "task_key"),
                                                        e);
                                            }
                                        }
                                    });
            drive(t);
            beat.interrupt();
            beat = null;
            transientCounts.remove(key);
        } catch (RuntimeException e) {
            if (beat != null) {
                beat.interrupt();
                beat = null;
            }
            taskError(t, key, e);
        } finally {
            if (beat != null) {
                beat.interrupt();
            }
            runningHere.remove(key);
        }
    }

    private void taskError(Map<String, Object> t, String key, RuntimeException e) {
        int n = DbErrors.isLockConflict(e) ? transientCounts.getOrDefault(key, 0) + 1 : 0;
        try {
            if (n > 0 && n <= TRANSIENT_MAX) {
                transientCounts.put(key, n);
                log.warn(
                        "push_task_conflict org={} key={} attempt={} err={}",
                        R.s(t, "factory_code"),
                        R.s(t, "task_key"),
                        n,
                        e.toString());
                DbErrors.retryDeadlock(
                        () -> {
                            endTask(
                                    t,
                                    "WAITING",
                                    "数据库繁忙（锁冲突），" + TRANSIENT_RETRY_SECONDS + " 秒后自动继续推送",
                                    Util.now().plusSeconds(TRANSIENT_RETRY_SECONDS),
                                    null);
                            return null;
                        },
                        DbErrors.DEADLOCK_ATTEMPTS,
                        false);
                return;
            }
            transientCounts.remove(key);
            log.error(
                    "push_task_failed org={} key={}",
                    R.s(t, "factory_code"),
                    R.s(t, "task_key"),
                    e);
            DbErrors.retryDeadlock(
                    () -> {
                        endTask(t, "STOPPED", "推送任务异常：" + e.getClass().getSimpleName(), null, null);
                        return null;
                    },
                    DbErrors.DEADLOCK_ATTEMPTS,
                    false);
        } catch (RuntimeException ex) {
            log.error(
                    "push_task_end_failed org={} key={}",
                    R.s(t, "factory_code"),
                    R.s(t, "task_key"),
                    ex);
        }
    }

    private void drive(Map<String, Object> t) {
        boolean resend = R.b(t, "resend");
        Set<Long> seenResend = new HashSet<>();
        String org = R.s(t, "factory_code");
        String key = R.s(t, "task_key");
        while (true) {
            Next nxt = nextBatch(t, resend);
            if (nxt == null) {
                long other = XO_KEY.equals(key) ? 0 : mapper.otherDelivered(org, key);
                long sent = mapper.sentBatches(org, key);
                endTask(
                        t,
                        "DONE",
                        "已推送 " + sent + " 批" + (other > 0 ? "；其余 " + other + " 枚已由 MES 拉取" : ""),
                        null,
                        null);
                return;
            }
            if (nxt.batch() == null) {
                if ("WAIT".equals(nxt.kind())) {
                    waitFor(t, batches.mustBatch(nxt.info(), false));
                } else {
                    endTask(t, "STOPPED", nxt.info(), null, null);
                }
                return;
            }
            Map<String, Object> b0 = nxt.batch();
            long id = R.l(b0, "id");
            if (resend && seenResend.contains(id)) {
                endTask(t, "STOPPED", "批次 " + R.s(b0, "batch_no") + " 续推仍未确认", null, null);
                return;
            }
            if (resend && FAILED.equals(R.s(b0, "result"))) {
                seenResend.add(id);
            }
            renew(t, R.s(b0, "batch_no"));
            if ("CONFIRMED".equals(pushOnce(b0))) {
                continue;
            }
            Map<String, Object> b = batches.mustBatch(R.s(b0, "batch_no"), false);
            String result = R.s(b, "result");
            if (SUCCESS.equals(result) || VOIDED.equals(result)) {
                continue;
            }
            String ps = R.s(b, "push_state");
            if ("WAIT_RETRY".equals(ps) || "WAIT_RECEIPT".equals(ps)) {
                waitFor(t, b);
                return;
            }
            String why = R.s(b, "fail_reason");
            endTask(
                    t,
                    "STOPPED",
                    "批次 "
                            + R.s(b, "batch_no")
                            + "："
                            + (why == null || why.isEmpty() ? "自动重试用尽仍未确认" : why),
                    null,
                    R.s(b, "batch_no"));
            return;
        }
    }

    /** 任务等这一批（等回执或等重试）到截止时间；③ 回执在设为等待之前已到的，马上再跑。 */
    private void waitFor(Map<String, Object> t, Map<String, Object> b) {
        endTask(t, "WAITING", waitMessage(b), R.t(b, "next_retry_at"), R.s(b, "batch_no"));
        Map<String, Object> cur = batches.mustBatch(R.s(b, "batch_no"), false);
        if (!java.util.Objects.equals(R.s(cur, "result"), R.s(b, "result"))
                || !java.util.Objects.equals(R.s(cur, "push_state"), R.s(b, "push_state"))) {
            receipts.wakeTask(cur, false);
        }
    }

    MesPushPort.Target target(FactoryEntity f) {
        String cred = secretBox.open(f.mesCredential);
        return new MesPushPort.Target(
                f.mesUrl, f.mesAuthType, cred == null ? "" : cred, f.pushTimeoutSec, f.mesGzip);
    }

    /** 发送一次。返回 CONFIRMED / WAIT_RECEIPT / FAILED / RETRY / EXHAUSTED / SKIPPED。 */
    String pushOnce(Map<String, Object> b0) {
        Map<String, Object> b = batches.mustBatch(R.s(b0, "batch_no"), false);
        String result = R.s(b, "result");
        if (!UNCONFIRMED.equals(result) && !FAILED.equals(result)) {
            return SUCCESS.equals(result) ? "CONFIRMED" : "SKIPPED";
        }
        FactoryEntity f = factories.mustGet(R.s(b, "factory_code"));
        int attempt = R.i(b, "push_count") + 1;
        long id = R.l(b, "id");
        long seen = mapper.lastReceiptId(id);
        MesPushPort.Outcome out =
                mes.send(target(f), R.s(b, "batch_no"), (byte[]) b.get("body_gz"), attempt, false);
        LocalDateTime now = Util.now();
        CurrentUser sys = CurrentUser.system();
        mapper.countPush(id, now);
        if (MesPushPort.RECEIVED.equals(out.kind())) {
            List<Integer> waits = settings.pushReceiptWaits();
            String state =
                    tx.run(
                            () -> {
                                Map<String, Object> cur =
                                        batches.mustBatch(R.s(b, "batch_no"), true);
                                String cr = R.s(cur, "result");
                                if (SUCCESS.equals(cr)) {
                                    return "CONFIRMED";
                                }
                                if ((!UNCONFIRMED.equals(cr) && !FAILED.equals(cr))
                                        || (FAILED.equals(cr)
                                                && (UNCONFIRMED.equals(result)
                                                        || mapper.receiptAfter(id, seen)))) {
                                    return "FAILED";
                                }
                                int wait =
                                        waits.get(
                                                Math.min(
                                                        R.i(cur, "receipt_timeouts"),
                                                        waits.size() - 1));
                                mapper.waitReceipt(id, now.plusMinutes(wait), now);
                                return "WAIT_RECEIPT";
                            });
            audit.recordIsolated(
                    sys,
                    AuditEntryDTO.of(AuditAction.SN_PUSH)
                            .factory(R.s(b, "factory_code"))
                            .pi(R.s(b, "pi_no"))
                            .count(R.l(b, "qty"))
                            .batch(R.s(b, "batch_no"))
                            .info("attempt=" + attempt + " received next=" + state));
            if (!"OK".equals(f.cfgStatus)) {
                mapper.factoryCfgOk(f.factoryCode);
            }
            return state;
        }
        if (MesPushPort.RETRY.equals(out.kind())) {
            List<Integer> intervals = settings.pushRetryIntervals();
            return tx.run(
                    () -> {
                        Map<String, Object> cur = batches.mustBatch(R.s(b, "batch_no"), true);
                        if (SUCCESS.equals(R.s(cur, "result"))) {
                            return "CONFIRMED";
                        }
                        String state;
                        int rc = R.i(cur, "retry_count");
                        if (rc < intervals.size()) {
                            long wait = intervals.get(rc) * 60L;
                            if (out.retryAfter() != null) {
                                wait = Math.max(wait, out.retryAfter());
                            }
                            mapper.waitRetry(
                                    id,
                                    now.plusSeconds(wait),
                                    out.code(),
                                    Util.cut(out.message(), 1000),
                                    now);
                            state = "RETRY";
                        } else {
                            mapper.retryExhausted(
                                    id, out.code(), Util.cut(out.message(), 1000), now);
                            state = "EXHAUSTED";
                        }
                        audit.record(
                                sys,
                                AuditEntryDTO.of(AuditAction.SN_PUSH)
                                        .fail(out.message())
                                        .factory(R.s(b, "factory_code"))
                                        .pi(R.s(b, "pi_no"))
                                        .count(R.l(b, "qty"))
                                        .batch(R.s(b, "batch_no"))
                                        .info(
                                                "attempt="
                                                        + attempt
                                                        + " code="
                                                        + out.code()
                                                        + " next="
                                                        + state));
                        return state;
                    });
        }
        return tx.run(
                () -> {
                    Map<String, Object> cur = batches.mustBatch(R.s(b, "batch_no"), true);
                    if (SUCCESS.equals(R.s(cur, "result"))) {
                        return "CONFIRMED";
                    }
                    boolean voidable = MesWire.VOIDABLE_CODES.contains(out.code());
                    receipts.markFailed(cur, out.code(), out.message(), voidable, now);
                    if ("AUTH".equals(out.code()) || "ADDRESS".equals(out.code())) {
                        mapper.factoryCfgFailed(f.factoryCode, Util.cut(out.message(), 500));
                    }
                    audit.record(
                            sys,
                            AuditEntryDTO.of(AuditAction.SN_PUSH)
                                    .fail(out.message())
                                    .factory(R.s(b, "factory_code"))
                                    .pi(R.s(b, "pi_no"))
                                    .count(R.l(b, "qty"))
                                    .batch(R.s(b, "batch_no"))
                                    .info(
                                            "attempt="
                                                    + attempt
                                                    + " code="
                                                    + out.code()
                                                    + " http="
                                                    + (out.httpStatus() == null
                                                            ? "None"
                                                            : out.httpStatus())));
                    return "FAILED";
                });
    }
}
