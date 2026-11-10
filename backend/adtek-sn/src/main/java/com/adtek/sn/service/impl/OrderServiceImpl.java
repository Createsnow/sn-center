package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.integration.K3GatewayPort;
import com.adtek.framework.lock.NamedLockService;
import com.adtek.framework.task.BackgroundTasks;
import com.adtek.framework.task.RecurringTask;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.dto.LineRefDTO;
import com.adtek.sn.mapper.OrderMapper;
import com.adtek.sn.service.OrderService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnCodec;
import com.adtek.sn.support.SnLocks;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.BillStateVO;
import com.adtek.sn.vo.DepartmentVO;
import com.adtek.sn.vo.OrderAlertVO;
import com.adtek.sn.vo.OrderBillVO;
import com.adtek.sn.vo.OrderLineVO;
import com.adtek.sn.vo.OrderMetaVO;
import com.adtek.sn.vo.OrderTargetVO;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.RevokeConfigVO;
import com.adtek.sn.vo.SyncProgressVO;
import com.adtek.sn.vo.SyncResultVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 金蝶生产订单快照（原系统 app/services/orders.py）：已审核单据的每条物料行一行，物料行 = 单据编号 + 序号。
 *
 * <p>同步方式：比对同步（默认每 10 分钟；只拉编号与修改时间比对，变动的整单替换、移出的删除，随即做订单变更比对，并复查反审核中的已出号单据）、
 * 按单据编号同步、进页同步（后台触发一次比对）、全量重建（写入新表后原子换表）。各种同步共用一把同步锁。
 */
@Service
public class OrderServiceImpl implements OrderService, RecurringTask {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);
    static final int SCHEDULE_SLACK_SECONDS = 30;
    private static final DateTimeFormatter K3_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderMapper mapper;
    private final ChangeServiceImpl changes;
    private final K3ServiceImpl k3;
    private final FactoryServiceImpl factories;
    private final RuleServiceImpl rules;
    private final AuditServiceImpl audit;
    private final SnLocks locks;
    private final SnSettings settings;
    private final BackgroundTasks background;
    private final Tx tx;
    private final AtomicBoolean pageThread = new AtomicBoolean();

    /** 本进程正在运行的同步（同步锁保证同一时间只有一个）。 */
    private volatile Progress running;

    public OrderServiceImpl(
            OrderMapper mapper,
            ChangeServiceImpl changes,
            K3ServiceImpl k3,
            FactoryServiceImpl factories,
            @Lazy RuleServiceImpl rules,
            AuditServiceImpl audit,
            SnLocks locks,
            SnSettings settings,
            BackgroundTasks background,
            Tx tx) {
        this.mapper = mapper;
        this.changes = changes;
        this.k3 = k3;
        this.factories = factories;
        this.rules = rules;
        this.audit = audit;
        this.locks = locks;
        this.settings = settings;
        this.background = background;
        this.tx = tx;
    }

    private CurrentUser operator() {
        return CurrentUser.current(settings.initAdminEmpNo()).requireOperator();
    }

    // ================================================================== 写快照

    private static List<Map<String, Object>> args(List<K3GatewayPort.Row> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (K3GatewayPort.Row r : rows) {
            if (!seen.add(R.key(r.billNo(), r.seq()))) {
                continue;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("billNo", r.billNo());
            m.put("seq", r.seq());
            m.put("customerCode", Util.cut(r.customerCode(), 64));
            m.put("pi", Util.cut(r.pi(), 64));
            m.put("po", Util.cut(r.po(), 128));
            m.put("orgCode", Util.cut(r.orgCode(), 64));
            m.put("orgName", Util.cut(r.orgName(), 128));
            m.put("attachNo", Util.cut(r.attachNo(), 64));
            m.put("materialCode", Util.cut(r.materialCode(), 64));
            m.put("materialName", Util.cut(r.materialName(), 255));
            m.put("lengthRaw", Util.cut(r.lengthRaw(), 64));
            m.put("unit", Util.cut(r.unit(), 64));
            m.put("qty", r.qty());
            m.put("customerPn", Util.cut(r.customerPn(), 128));
            m.put("customerDesc", Util.cut(r.customerDesc(), 500));
            m.put("specDesc", Util.cut(r.specDesc(), 500));
            m.put("materialSpec", Util.cut(r.materialSpec(), 500));
            m.put("deptCode", Util.cut(r.deptCode(), 64));
            m.put("deptName", Util.cut(r.deptName(), 128));
            m.put("modifyTime", r.modifyTime());
            out.add(m);
        }
        return out;
    }

    private void insert(boolean newTable, List<Map<String, Object>> rows, LocalDateTime ts) {
        for (List<Map<String, Object>> part : R.chunks(rows, 1000)) {
            if (newTable) {
                mapper.insertRowsNew(part, ts);
            } else {
                mapper.insertRows(part, ts);
            }
        }
    }

    /** 同一事务内先删该单全部行再插入（金蝶删掉的物料行随之消失）。 */
    public void replaceBills(Map<String, List<K3GatewayPort.Row>> bills, LocalDateTime ts) {
        for (Map.Entry<String, List<K3GatewayPort.Row>> e : bills.entrySet()) {
            mapper.deleteBill(e.getKey());
            List<Map<String, Object>> a = args(e.getValue());
            if (!a.isEmpty()) {
                insert(false, a, ts);
            }
        }
    }

    private void removeBills(List<String> nos) {
        for (List<String> part : R.chunks(nos, 500)) {
            mapper.removeBills(part);
        }
    }

    private static Map<String, List<K3GatewayPort.Row>> group(List<K3GatewayPort.Row> rows) {
        Map<String, List<K3GatewayPort.Row>> out = new LinkedHashMap<>();
        for (K3GatewayPort.Row r : rows) {
            out.computeIfAbsent(r.billNo(), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    // ================================================================== 同步进度

    private final class Progress {
        final String mode;
        final String trigger;
        final String operator;
        final String billNo;
        final LocalDateTime startedAt = Util.now();
        volatile String stage = "";
        volatile long done;
        volatile long total;

        Progress(CurrentUser cu, String mode, String trigger, String billNo) {
            this.mode = mode;
            this.trigger = trigger;
            this.operator = cu.empNo() == null ? "" : cu.empNo();
            this.billNo = billNo;
            running = this;
        }

        void stage(String s, long t) {
            stage = s;
            done = 0;
            total = Math.max(0, t);
        }

        void close() {
            if (running == this) {
                running = null;
            }
        }
    }

    @Override
    public SyncProgressVO syncProgress() {
        operator();
        Progress p = running;
        SyncProgressVO v = new SyncProgressVO();
        if (p == null) {
            v.running = locks.syncRunning();
            if (v.running) {
                v.mode = "";
            }
            return v;
        }
        v.running = true;
        v.mode = p.mode;
        v.trigger = p.trigger;
        v.operator = p.operator;
        v.stage = p.stage;
        v.done = p.done;
        v.total = p.total;
        v.billNo = p.billNo;
        v.startedAt = p.startedAt;
        v.elapsedSec = Math.max(0, Duration.between(p.startedAt, Util.now()).getSeconds());
        return v;
    }

    // ================================================================== 比对同步

    /** 比对同步；拿不到同步锁（已有同步在运行）返回 null。trigger = SCHEDULED / PAGE / MANUAL。 */
    public SyncResultVO compareSync(CurrentUser cu, String trigger) {
        Optional<NamedLockService.Held> held = locks.syncLock(false);
        if (held.isEmpty()) {
            return null;
        }
        try {
            if ("SCHEDULED".equals(trigger) && comparedRecently()) {
                return null;
            }
            Progress progress = new Progress(cu, "COMPARE", trigger, "");
            try {
                return doCompare(cu, trigger, progress);
            } finally {
                progress.close();
            }
        } finally {
            held.get().release();
        }
    }

    private boolean comparedRecently() {
        Map<String, Object> st = mapper.syncState();
        LocalDateTime last = R.t(st, "last_run_at");
        if (last == null) {
            return false;
        }
        long gap = Math.max(0, settings.syncIntervalMinutes() * 60L - SCHEDULE_SLACK_SECONDS);
        return Duration.between(last, Util.now()).getSeconds() < gap;
    }

    private SyncResultVO doCompare(CurrentUser cu, String trigger, Progress progress) {
        LocalDateTime ts = Util.now();
        Map<String, List<K3GatewayPort.Row>> fetched;
        Map<String, LocalDateTime> remote = new LinkedHashMap<>();
        Map<String, LocalDateTime> local = new HashMap<>();
        Set<String> localKeys = new HashSet<>();
        List<String> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        try {
            progress.stage("HEADS", 0);
            for (K3GatewayPort.Head h : k3.fetchApprovedHeads()) {
                remote.put(h.billNo(), h.modifyTime());
            }
            for (Map<String, Object> r : mapper.localHeads()) {
                local.put(R.s(r, "bill_no"), R.t(r, "t"));
                localKeys.add(R.s(r, "bill_no"));
            }
            remote.forEach(
                    (no, t) -> {
                        if (!localKeys.contains(no)
                                || t == null
                                || !Objects.equals(local.get(no), t)) {
                            changed.add(no);
                        }
                    });
            for (String no : localKeys) {
                if (!remote.containsKey(no)) {
                    removed.add(no);
                }
            }
            progress.stage("DETAILS", changed.size());
            fetched =
                    changed.isEmpty()
                            ? Map.of()
                            : group(k3.fetchBills(changed, n -> progress.done = n));
        } catch (BusinessException e) {
            mapper.finishFail(ts, Util.cut(Biz.message(e), 500));
            audit.recordIsolated(
                    cu,
                    AuditEntryDTO.of(AuditAction.ORDER_SYNC)
                            .fail(Biz.message(e))
                            .info("mode=compare trigger=" + trigger));
            throw e;
        }
        for (String no : changed) {
            if (!fetched.containsKey(no) && localKeys.contains(no)) {
                removed.add(no);
            }
        }
        long rows = fetched.values().stream().mapToLong(List::size).sum();
        progress.stage("WRITE", 0);
        Map<String, List<K3GatewayPort.Row>> f = fetched;
        tx.exec(
                () -> {
                    replaceBills(f, ts);
                    removeBills(removed);
                });
        List<String> notes = changes.afterSync(cu, new ArrayList<>(fetched.keySet()), removed);
        changes.recheckReversed(cu, remote.keySet());
        int nBills = fetched.size() + removed.size();
        mapper.finishOk(ts, nBills);
        if (nBills > 0) {
            audit.recordIsolated(
                    cu,
                    AuditEntryDTO.of(AuditAction.ORDER_SYNC)
                            .count(nBills)
                            .info(
                                    "mode=compare trigger="
                                            + trigger
                                            + " replaced="
                                            + fetched.size()
                                            + " removed="
                                            + removed.size()
                                            + " rows="
                                            + rows));
        } else {
            changes.countDaily("ORDER_SYNC_NOCHANGE", "");
        }
        SyncResultVO v = new SyncResultVO();
        v.mode = "compare";
        v.bills = (long) nBills;
        v.replaced = (long) fetched.size();
        v.removed = (long) removed.size();
        v.rows = rows;
        v.syncedAt = ts;
        v.attachK3Filled = notes.stream().filter(ChangeServiceImpl.ATTACH::equals).count();
        return v;
    }

    @Override
    public String name() {
        return "sn-order-sync";
    }

    @Override
    public long intervalSeconds() {
        return settings.syncIntervalMinutes() * 60L;
    }

    @Override
    public boolean enabled() {
        return settings.syncEnabled();
    }

    /** 后台定时：每个周期一次（同步锁防同时执行，按上次比对同步时间去重）。 */
    @Override
    public void run() {
        try {
            compareSync(CurrentUser.system(), "SCHEDULED");
        } catch (BusinessException e) {
            log.warn("compare_sync_failed err={}", Biz.message(e));
        }
    }

    @Override
    public SyncResultVO compare() {
        CurrentUser cu = operator();
        SyncResultVO r = compareSync(cu, "MANUAL");
        if (r == null) {
            r = new SyncResultVO();
            r.skipped = true;
        }
        return r;
    }

    /** 进页同步：后台触发一次比对同步，立即返回；正有同步在运行时跳过。 */
    @Override
    public SyncResultVO pageSync() {
        CurrentUser cu = operator();
        SyncResultVO v = new SyncResultVO();
        if (!pageThread.compareAndSet(false, true)) {
            v.started = false;
            return v;
        }
        try {
            background.submit(
                    "sn-page-sync",
                    () -> {
                        try {
                            compareSync(cu, "PAGE");
                        } catch (RuntimeException e) {
                            log.warn("page_sync_failed err={}", e.toString());
                        } finally {
                            pageThread.set(false);
                        }
                    });
        } catch (RuntimeException e) {
            pageThread.set(false);
            throw e;
        }
        v.started = true;
        return v;
    }

    // ================================================================== 按单据同步

    @Override
    public SyncResultVO syncBill(String billNo) {
        CurrentUser cu = operator();
        String no = Util.trim(billNo);
        if (no.isEmpty()) {
            throw Biz.err(SnErrorCode.ORDER_BILL_REQUIRED);
        }
        NamedLockService.Held held =
                locks.syncLock(true).orElseThrow(() -> Biz.err(SnErrorCode.ORDER_SYNC_BUSY));
        Progress progress = new Progress(cu, "BILL", "MANUAL", no);
        K3GatewayPort.BillView v;
        LocalDateTime ts;
        String outcome;
        try {
            v = k3.viewBill(no);
            ts = Util.now();
            boolean had = mapper.existsBill(no);
            if (v.found() && "C".equals(v.docStatus())) {
                K3GatewayPort.BillView fv = v;
                tx.exec(
                        () -> {
                            replaceBills(Map.of(no, fv.rows()), ts);
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.ORDER_SYNC)
                                            .bill(no)
                                            .count(1)
                                            .info("mode=bill rows=" + fv.rows().size()));
                        });
                changes.afterSync(cu, List.of(no), List.of());
                outcome = "REPLACED";
            } else {
                K3GatewayPort.BillView fv = v;
                tx.exec(
                        () -> {
                            removeBills(List.of(no));
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.ORDER_SYNC)
                                            .bill(no)
                                            .count(had ? 1 : 0)
                                            .info(
                                                    "mode=bill found="
                                                            + (fv.found() ? "True" : "False")
                                                            + " status="
                                                            + (Util.blank(fv.docStatus())
                                                                    ? "-"
                                                                    : fv.docStatus())));
                        });
                changes.afterRemoved(cu, no, v);
                outcome = v.found() ? (had ? "NOT_APPROVED" : "NOT_APPROVED_NEW") : "NOT_FOUND";
            }
        } finally {
            progress.close();
            held.release();
        }
        SyncResultVO r = new SyncResultVO();
        r.mode = "bill";
        r.billNo = no;
        r.outcome = outcome;
        r.docStatus = v.docStatus();
        r.rows = "REPLACED".equals(outcome) ? (long) v.rows().size() : 0L;
        r.syncedAt = ts;
        return r;
    }

    // ================================================================== 全量重建

    @Override
    public SyncResultVO rebuild() {
        CurrentUser cu = CurrentUser.current(settings.initAdminEmpNo()).requireAdmin();
        NamedLockService.Held held =
                locks.syncLock(true).orElseThrow(() -> Biz.err(SnErrorCode.ORDER_SYNC_BUSY));
        Progress progress = new Progress(cu, "REBUILD", "MANUAL", "");
        int bills;
        int total;
        LocalDateTime ts;
        try {
            progress.stage("DETAILS", mapper.countRows());
            List<K3GatewayPort.Row> rows = k3.fetchAll(n -> progress.done = n);
            progress.stage("WRITE", 0);
            try {
                factories.syncFromK3(cu);
            } catch (RuntimeException e) {
                log.warn("factory_sync_during_rebuild_failed err={}", e.toString());
            }
            ts = Util.now();
            mapper.dropNew();
            mapper.dropOld();
            mapper.createNew();
            try {
                List<Map<String, Object>> a = args(rows);
                tx.exec(() -> insert(true, a, ts));
                mapper.swapNew();
                mapper.dropOld();
            } catch (RuntimeException e) {
                mapper.dropNew();
                throw e;
            }
            bills = rows.stream().map(K3GatewayPort.Row::billNo).collect(Collectors.toSet()).size();
            total = rows.size();
            mapper.setLastFull(ts);
            audit.recordIsolated(
                    cu,
                    AuditEntryDTO.of(AuditAction.ORDER_SYNC)
                            .count(bills)
                            .info("mode=rebuild rows=" + total));
            changes.afterRebuild(cu);
        } finally {
            progress.close();
            held.release();
        }
        SyncResultVO r = new SyncResultVO();
        r.mode = "rebuild";
        r.bills = (long) bills;
        r.rows = (long) total;
        r.syncedAt = ts;
        return r;
    }

    // ================================================================== 附件项次、米数、产品单位

    /** 附件项次、米数、产品单位能否在页面修改：物料行挂着号时不能改（有已分配的号为 ALLOCATED，否则 GENERATED）。 */
    public static String editLock(Map<String, Object> count) {
        if (count == null || R.l(count, "gen_count") <= 0) {
            return null;
        }
        if (R.l(count, "alloc_count") > 0) {
            return "ALLOCATED";
        }
        return "GENERATED";
    }

    private void checkEditable(String no, int seq) {
        String lock = editLock(mapper.lineCount(no, seq));
        if ("ALLOCATED".equals(lock)) {
            throw Biz.err(SnErrorCode.LINE_EDIT_ALLOCATED, "bill_no", no, "seq", seq);
        }
        if ("GENERATED".equals(lock)) {
            throw Biz.err(SnErrorCode.LINE_EDIT_GENERATED, "bill_no", no, "seq", seq);
        }
    }

    private Map<String, Object> mustLine(String no, Integer seq) {
        Map<String, Object> line = mapper.line(no, seq);
        if (line == null) {
            throw Biz.err(SnErrorCode.ORDER_LINE_NOT_FOUND, "bill_no", no, "seq", seq);
        }
        return line;
    }

    /** 生效值与来源。 */
    public record Effective(String value, String source) {}

    /** 附件项次：MANUAL 页面填写（优先）/ K3 金蝶值 / EMPTY 待填写；填写后同序号的物料变了，填写值作废。 */
    public static Effective effectiveAttach(Map<String, Object> line, Map<String, Object> fill) {
        if (fill != null
                && Objects.equals(R.s(fill, "material_code"), R.sz(line, "material_code"))) {
            return new Effective(R.s(fill, "manual_value"), "MANUAL");
        }
        String k3v = Util.trim(R.s(line, "attach_no"));
        if (!k3v.isEmpty()) {
            return new Effective(k3v, "K3");
        }
        return new Effective("", "EMPTY");
    }

    /** 米数：MANUAL / K3（原样，含中文也照用）/ EMPTY。 */
    public static Effective effectiveLength(Map<String, Object> line, Map<String, Object> fill) {
        if (fill != null
                && Objects.equals(R.s(fill, "material_code"), R.sz(line, "material_code"))) {
            return new Effective(R.s(fill, "manual_value"), "MANUAL");
        }
        String k3v = SnCodec.cleanLength(R.s(line, "length_raw"));
        if (k3v != null) {
            return new Effective(k3v, "K3");
        }
        return new Effective("", "EMPTY");
    }

    /** 产品单位：MANUAL / K3 / EMPTY。 */
    public static Effective effectiveUnit(Map<String, Object> line, Map<String, Object> fill) {
        if (fill != null
                && Objects.equals(R.s(fill, "material_code"), R.sz(line, "material_code"))) {
            return new Effective(R.s(fill, "manual_value"), "MANUAL");
        }
        String k3v = Util.trim(R.s(line, "unit"));
        if (!k3v.isEmpty()) {
            return new Effective(k3v, "K3");
        }
        return new Effective("", "EMPTY");
    }

    @Override
    public OrderLineVO fillAttach(String billNo, Integer seq, String value) {
        CurrentUser cu = operator();
        String no = Util.trim(billNo);
        String v = Util.trim(value);
        if (v.length() > 64) {
            throw Biz.err(SnErrorCode.ATTACH_INVALID);
        }
        Map<String, Object> first = mustLine(no, seq);
        cu.checkWrite(R.s(first, "org_code"));
        Map<String, Object> line =
                locks.withPiLock(
                        () ->
                                tx.run(
                                        () -> {
                                            Map<String, Object> l = mustLine(no, seq);
                                            int s = R.i(l, "seq");
                                            String k3v = Util.trim(R.s(l, "attach_no"));
                                            if (v.isEmpty() && k3v.isEmpty()) {
                                                throw Biz.err(SnErrorCode.ATTACH_INVALID);
                                            }
                                            checkEditable(no, s);
                                            Map<String, Object> prev = mapper.lockAttach(no, s);
                                            LocalDateTime now = Util.now();
                                            if (v.isEmpty()) {
                                                mapper.deleteAttach(no, s);
                                            } else {
                                                mapper.upsertAttach(
                                                        no,
                                                        s,
                                                        v,
                                                        R.s(l, "material_code"),
                                                        k3v,
                                                        cu.empNo(),
                                                        now);
                                            }
                                            audit.record(
                                                    cu,
                                                    AuditEntryDTO.of(AuditAction.ORDER_ATTACH_FILL)
                                                            .bill(no)
                                                            .pi(R.s(l, "pi"))
                                                            .factory(R.s(l, "org_code"))
                                                            .material(R.s(l, "material_code"))
                                                            .info(
                                                                    "seq="
                                                                            + s
                                                                            + " "
                                                                            + (prev == null
                                                                                    ? ""
                                                                                    : R.s(
                                                                                            prev,
                                                                                            "manual_value"))
                                                                            + " -> "
                                                                            + (v.isEmpty()
                                                                                    ? "K3:" + k3v
                                                                                    : v)));
                                            return l;
                                        }),
                        R.s(first, "pi"));
        return lineViewOf(line);
    }

    private static String lengthValue(String value) {
        String v = SnCodec.cleanLength(value);
        if (v != null && v.length() > 64) {
            throw Biz.err(SnErrorCode.LENGTH_FILL_INVALID);
        }
        if (v != null && SnCodec.hasChinese(v)) {
            throw Biz.err(SnErrorCode.LENGTH_FILL_CHINESE, "value", v);
        }
        return v;
    }

    private static String unitValue(String value) {
        String v = Util.trim(value);
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() > 64) {
            throw Biz.err(SnErrorCode.UNIT_FILL_INVALID);
        }
        if (SnCodec.hasChinese(v)) {
            throw Biz.err(SnErrorCode.UNIT_FILL_CHINESE, "value", v);
        }
        return v;
    }

    @Override
    public OrderLineVO fillLength(String billNo, Integer seq, String value) {
        return fillLengthOrUnit(billNo, seq, lengthValue(value), true);
    }

    @Override
    public OrderLineVO fillUnit(String billNo, Integer seq, String value) {
        return fillLengthOrUnit(billNo, seq, unitValue(value), false);
    }

    private OrderLineVO fillLengthOrUnit(String billNo, Integer seq, String value, boolean length) {
        CurrentUser cu = operator();
        String no = Util.trim(billNo);
        Map<String, Object> first = mustLine(no, seq);
        cu.checkWrite(R.s(first, "org_code"));
        Map<String, Object> line =
                locks.withPiLock(
                        () ->
                                tx.run(
                                        () -> {
                                            Map<String, Object> l = mustLine(no, seq);
                                            int s = R.i(l, "seq");
                                            checkEditable(no, s);
                                            Map<String, Object> prev =
                                                    length
                                                            ? mapper.lockLength(no, s)
                                                            : mapper.lockUnit(no, s);
                                            LocalDateTime now = Util.now();
                                            String k3v =
                                                    length
                                                            ? SnCodec.cleanLength(
                                                                    R.s(l, "length_raw"))
                                                            : Util.trim(R.s(l, "unit"));
                                            String v = value;
                                            if (v == null || v.equals(k3v)) {
                                                v = null;
                                                if (length) {
                                                    mapper.deleteLength(no, s);
                                                } else {
                                                    mapper.deleteUnit(no, s);
                                                }
                                            } else if (length) {
                                                mapper.upsertLength(
                                                        no,
                                                        s,
                                                        v,
                                                        R.s(l, "material_code"),
                                                        cu.empNo(),
                                                        now);
                                            } else {
                                                mapper.upsertUnit(
                                                        no,
                                                        s,
                                                        v,
                                                        R.s(l, "material_code"),
                                                        cu.empNo(),
                                                        now);
                                            }
                                            String before =
                                                    prev == null ? null : R.s(prev, "manual_value");
                                            if (!Objects.equals(before, v)) {
                                                audit.record(
                                                        cu,
                                                        AuditEntryDTO.of(
                                                                        length
                                                                                ? AuditAction
                                                                                        .ORDER_LENGTH_FILL
                                                                                : AuditAction
                                                                                        .ORDER_UNIT_FILL)
                                                                .bill(no)
                                                                .pi(R.s(l, "pi"))
                                                                .factory(R.s(l, "org_code"))
                                                                .material(R.s(l, "material_code"))
                                                                .info(
                                                                        "seq="
                                                                                + s
                                                                                + " "
                                                                                + Util.nz(before)
                                                                                + " -> "
                                                                                + Util.nz(v)));
                                            }
                                            return l;
                                        }),
                        R.s(first, "pi"));
        return lineViewOf(line);
    }

    private OrderLineVO lineViewOf(Map<String, Object> line) {
        String k = R.key(line);
        Extras e = lineExtras(List.of(line));
        return lineView(
                line, e.counts.get(k), e.attaches.get(k), null, e.lengths.get(k), e.units.get(k));
    }

    @Override
    public RevokeConfigVO revokeConfig(String piIn, List<LineRefDTO> linesIn) {
        CurrentUser cu = operator();
        String pi = Util.trim(piIn);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        Set<String> keys = new LinkedHashSet<>();
        if (linesIn != null) {
            for (LineRefDTO x : linesIn) {
                keys.add(R.key(Util.trim(x.billNo), x.seq == null ? 0 : x.seq));
            }
        }
        if (keys.isEmpty()) {
            throw Biz.err(SnErrorCode.RULE_LINE_REQUIRED);
        }
        return locks.withPiLock(
                () ->
                        tx.run(
                                () -> {
                                    List<Map<String, Object>> rows = new ArrayList<>();
                                    for (Map<String, Object> r : mapper.linesOfPi(pi)) {
                                        if (keys.contains(R.key(r))) {
                                            rows.add(r);
                                        }
                                    }
                                    if (rows.size() != keys.size()) {
                                        throw Biz.err(SnErrorCode.GEN_SCOPE_INVALID);
                                    }
                                    cu.checkWrite(
                                            rows.stream()
                                                    .map(r -> R.s(r, "org_code"))
                                                    .distinct()
                                                    .toArray(String[]::new));
                                    Map<String, Map<String, Object>> counts = lineCounts(rows);
                                    List<Map<String, Object>> free = new ArrayList<>();
                                    for (Map<String, Object> r : rows) {
                                        if (editLock(counts.get(R.key(r))) == null) {
                                            free.add(r);
                                        }
                                    }
                                    List<String> details = new ArrayList<>();
                                    List<Map<String, Object>> done = new ArrayList<>();
                                    for (Map<String, Object> r : free) {
                                        String b = R.s(r, "bill_no");
                                        int s = R.i(r, "seq");
                                        List<String> gone = new ArrayList<>();
                                        if (mapper.deleteLineRule(b, s) > 0) {
                                            gone.add("rule");
                                        }
                                        if (mapper.deleteAttach(b, s) > 0) {
                                            gone.add("attach");
                                        }
                                        if (mapper.deleteLength(b, s) > 0) {
                                            gone.add("length");
                                        }
                                        if (mapper.deleteUnit(b, s) > 0) {
                                            gone.add("unit");
                                        }
                                        if (!gone.isEmpty()) {
                                            done.add(r);
                                            details.add(b + "/" + s + ":" + String.join("+", gone));
                                        }
                                    }
                                    if (!done.isEmpty()) {
                                        Set<String> billSet = new HashSet<>();
                                        Set<String> orgSet = new HashSet<>();
                                        for (Map<String, Object> r : done) {
                                            billSet.add(R.s(r, "bill_no"));
                                            orgSet.add(R.s(r, "org_code"));
                                        }
                                        audit.record(
                                                cu,
                                                AuditEntryDTO.of(AuditAction.LINE_CONFIG_REVOKE)
                                                        .pi(pi)
                                                        .bill(
                                                                billSet.size() == 1
                                                                        ? billSet.iterator().next()
                                                                        : null)
                                                        .factory(
                                                                orgSet.size() == 1
                                                                        ? orgSet.iterator().next()
                                                                        : null)
                                                        .count(done.size())
                                                        .info(String.join(" ", details)));
                                    }
                                    RevokeConfigVO v = new RevokeConfigVO();
                                    v.piNo = pi;
                                    v.revoked = done.size();
                                    v.skipped = rows.size() - free.size();
                                    return v;
                                }),
                pi);
    }

    // ================================================================== 查询

    @Override
    public PageVO<OrderBillVO> list(
            String q, String customer, String pi, String factoryCode, Integer p, Integer size) {
        CurrentUser cu = operator();
        PageReq page = PageReq.of(p, size);
        String text = Util.trim(q);
        String like = text.isEmpty() ? null : Util.likeAny(text);
        String c = Util.trimOrNull(customer);
        String pp = Util.trimOrNull(pi);
        List<String> scope = cu.readFactories(factoryCode);
        long total = mapper.countBills(like, c, pp, scope);
        List<Map<String, Object>> rows =
                mapper.pageBills(like, c, pp, scope, page.offset(), page.size());
        Map<String, BillStateVO> states =
                changes.billStates(rows.stream().map(r -> R.s(r, "bill_no")).toList());
        List<OrderBillVO> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            OrderBillVO v = new OrderBillVO();
            v.billNo = R.s(r, "bill_no");
            v.customerCode = R.s(r, "customer_code");
            v.po = R.s(r, "po");
            v.pi = R.s(r, "pi");
            v.orgCode = R.s(r, "org_code");
            v.orgName = R.s(r, "org_name");
            v.deptName = R.sz(r, "dept_name");
            v.docStatus = "C";
            v.modifyTime = k3Time(R.t(r, "modify_time"));
            v.totalQty = R.l(r, "total_qty");
            v.lineCount = R.l(r, "line_count");
            v.generatedQty = R.l(r, "gen_sum");
            v.quota = R.l(r, "quota");
            BillStateVO st = states.getOrDefault(v.billNo, BillStateVO.empty());
            v.suspend = st.suspend;
            v.leaveState = st.leaveState;
            v.piFrozen = st.piFrozen;
            v.openAlerts = st.openAlerts;
            v.issued = st.issued;
            items.add(v);
        }
        return PageVO.of(items, total, page.page(), page.size());
    }

    public OrderLineVO lineView(
            Map<String, Object> r,
            Map<String, Object> count,
            Map<String, Object> fill,
            Object rule,
            Map<String, Object> lfill,
            Map<String, Object> ufill) {
        long qty = snQty(r);
        long own = count == null ? 0 : R.l(count, "own_generated");
        Effective attach = effectiveAttach(r, fill);
        Effective length = effectiveLength(r, lfill);
        Effective unit = effectiveUnit(r, ufill);
        OrderLineVO v = new OrderLineVO();
        v.billNo = R.s(r, "bill_no");
        v.seq = R.i(r, "seq");
        v.customerCode = R.s(r, "customer_code");
        v.pi = R.s(r, "pi");
        v.po = R.s(r, "po");
        v.orgCode = R.s(r, "org_code");
        v.orgName = R.s(r, "org_name");
        v.attachNo = attach.value();
        v.attachSource = attach.source();
        v.attachK3 = R.s(r, "attach_no");
        v.attachManual = fill == null ? null : R.s(fill, "manual_value");
        v.attachK3Conflict =
                fill != null
                        && !R.sz(r, "attach_no").isEmpty()
                        && !Objects.equals(R.s(r, "attach_no"), R.s(fill, "manual_value"));
        v.materialCode = R.s(r, "material_code");
        v.materialName = R.s(r, "material_name");
        v.lengthRaw = R.s(r, "length_raw");
        v.length = length.value();
        v.lengthSource = length.source();
        v.lengthManual = lfill == null ? null : R.s(lfill, "manual_value");
        v.unitRaw = R.sz(r, "unit");
        v.unit = unit.value();
        v.unitSource = unit.source();
        v.unitManual = ufill == null ? null : R.s(ufill, "manual_value");
        v.qty = qty;
        v.customerPn = R.sz(r, "customer_pn");
        v.customerDesc = R.sz(r, "customer_desc");
        v.specDesc = R.sz(r, "spec_desc");
        v.materialSpec = R.sz(r, "material_spec");
        v.deptCode = R.sz(r, "dept_code");
        v.deptName = R.sz(r, "dept_name");
        v.generatedQty = count == null ? 0 : R.l(count, "gen_count");
        v.ownGeneratedQty = own;
        v.allocatedQty = count == null ? 0 : R.l(count, "alloc_count");
        v.editLock = editLock(count);
        v.quota = Math.max(0, qty - own);
        v.rule = rule;
        v.modifyTime = k3Time(R.t(r, "modify_time"));
        v.syncedAt = R.t(r, "synced_at");
        return v;
    }

    /** 金蝶修改日期按金蝶返回的时间原样显示（不做时区换算）。 */
    public static String k3Time(LocalDateTime v) {
        return v == null ? null : K3_TS.format(v);
    }

    /** 物料行数量：有小数时按整数部分计。 */
    public static long snQty(Map<String, Object> line) {
        BigDecimal q = R.d(line, "qty");
        return q == null ? 0 : Math.max(0, q.setScale(0, RoundingMode.DOWN).longValue());
    }

    /** 物料行附表：(bill, seq) → 行。 */
    public record Extras(
            Map<String, Map<String, Object>> counts,
            Map<String, Map<String, Object>> attaches,
            Map<String, Map<String, Object>> lengths,
            Map<String, Map<String, Object>> units) {}

    private static Set<String> billsOf(Collection<Map<String, Object>> lines) {
        Set<String> bills = new TreeSet<>();
        for (Map<String, Object> l : lines) {
            bills.add(R.s(l, "bill_no"));
        }
        return bills;
    }

    private static Map<String, Map<String, Object>> byLine(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : rows) {
            out.put(R.key(r), r);
        }
        return out;
    }

    public Map<String, Map<String, Object>> lineCounts(Collection<Map<String, Object>> lines) {
        Set<String> bills = billsOf(lines);
        return bills.isEmpty() ? new HashMap<>() : byLine(mapper.countsOf(bills));
    }

    public Map<String, Map<String, Object>> lineCountsOfBills(Collection<String> bills) {
        return bills.isEmpty() ? new HashMap<>() : byLine(mapper.countsOf(new TreeSet<>(bills)));
    }

    public Map<String, Map<String, Object>> lineLengths(Collection<Map<String, Object>> lines) {
        Set<String> bills = billsOf(lines);
        return bills.isEmpty() ? new HashMap<>() : byLine(mapper.lengthsOf(bills));
    }

    public Map<String, Map<String, Object>> lineAttaches(Collection<Map<String, Object>> lines) {
        Set<String> bills = billsOf(lines);
        return bills.isEmpty() ? new HashMap<>() : byLine(mapper.attachesOf(bills));
    }

    public Map<String, Map<String, Object>> lineUnits(Collection<Map<String, Object>> lines) {
        Set<String> bills = billsOf(lines);
        return bills.isEmpty() ? new HashMap<>() : byLine(mapper.unitsOf(bills));
    }

    public Extras lineExtras(Collection<Map<String, Object>> lines) {
        Set<String> bills = billsOf(lines);
        if (bills.isEmpty()) {
            return new Extras(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }
        return new Extras(
                byLine(mapper.countsOf(bills)),
                byLine(mapper.attachesOf(bills)),
                byLine(mapper.lengthsOf(bills)),
                byLine(mapper.unitsOf(bills)));
    }

    public Extras lineExtrasOfBills(Collection<String> billNos) {
        Set<String> bills = new TreeSet<>(billNos);
        if (bills.isEmpty()) {
            return new Extras(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }
        return new Extras(
                byLine(mapper.countsOf(bills)),
                byLine(mapper.attachesOf(bills)),
                byLine(mapper.lengthsOf(bills)),
                byLine(mapper.unitsOf(bills)));
    }

    /** 物料行带上生效的附件项次（eff_attach）与米数（eff_length）。 */
    public List<Map<String, Object>> effectiveLines(List<Map<String, Object>> ls) {
        Map<String, Map<String, Object>> lens = lineLengths(ls);
        Map<String, Map<String, Object>> fills = lineAttaches(ls);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : ls) {
            Map<String, Object> m = new HashMap<>(r);
            m.put("eff_attach", effectiveAttach(r, fills.get(R.key(r))).value());
            m.put("eff_length", effectiveLength(r, lens.get(R.key(r))).value());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> lines(String billNo) {
        return mapper.lines(Util.trim(billNo));
    }

    public List<Map<String, Object>> linesOfPi(String pi) {
        return mapper.linesOfPi(pi);
    }

    @Override
    public OrderBillVO billLines(String billNo) {
        CurrentUser cu = operator();
        return linesOf(cu, billNo);
    }

    public OrderBillVO linesOf(CurrentUser cu, String billNo) {
        List<Map<String, Object>> ls = lines(billNo);
        if (ls.isEmpty()) {
            throw Biz.err(SnErrorCode.ORDER_NOT_FOUND, "bill_no", Util.trim(billNo));
        }
        Map<String, Object> h = ls.getFirst();
        cu.checkRead(R.s(h, "org_code"));
        Extras e = lineExtras(ls);
        Map<String, Object> rmap = rules.lineRulesBrief(R.s(h, "pi"), ls);
        OrderBillVO v = new OrderBillVO();
        v.billNo = R.s(h, "bill_no");
        v.pi = R.s(h, "pi");
        v.po = R.s(h, "po");
        v.customerCode = R.s(h, "customer_code");
        v.orgCode = R.s(h, "org_code");
        v.orgName = R.s(h, "org_name");
        v.deptName =
                ls.stream()
                        .map(r -> R.s(r, "dept_name"))
                        .filter(d -> d != null && !d.isEmpty())
                        .collect(Collectors.toCollection(TreeSet::new))
                        .stream()
                        .collect(Collectors.joining("、"));
        v.docStatus = "C";
        v.modifyTime = k3Time(R.t(h, "modify_time"));
        v.state = changes.billStates(List.of(v.billNo)).getOrDefault(v.billNo, BillStateVO.empty());
        v.lines = new ArrayList<>();
        for (Map<String, Object> r : ls) {
            String k = R.key(r);
            v.lines.add(
                    lineView(
                            r,
                            e.counts.get(k),
                            e.attaches.get(k),
                            rmap.get(k),
                            e.lengths.get(k),
                            e.units.get(k)));
        }
        return v;
    }

    @Override
    public List<DepartmentVO> departments(String factoryCode) {
        CurrentUser cu = operator();
        List<DepartmentVO> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.departments(cu.readFactories(factoryCode))) {
            DepartmentVO v = new DepartmentVO();
            v.orgCode = R.s(r, "org_code");
            v.orgName = R.s(r, "org_name");
            v.deptCode = R.s(r, "dept_code");
            v.deptName = R.s(r, "dept_name");
            v.bills = R.l(r, "bills");
            v.lineCount = R.l(r, "line_count");
            v.modifyTime = k3Time(R.t(r, "modify_time"));
            out.add(v);
        }
        return out;
    }

    @Override
    public OrderMetaVO meta() {
        CurrentUser cu = operator();
        List<String> scope = cu.readFactories(null);
        Map<String, Object> m = mapper.meta(scope);
        Map<String, Object> st = mapper.syncState();
        OrderMetaVO v = new OrderMetaVO();
        v.syncedAt = R.t(m, "ts");
        v.bills = R.l(m, "bills");
        v.rows = R.l(m, "row_count");
        v.emptyCustomerBills = R.l(m, "empty_customer");
        v.attachPending = mapper.pendingAttach(scope);
        OrderMetaVO.Compare c = new OrderMetaVO.Compare();
        c.lastRunAt = R.t(st, "last_run_at");
        c.lastOk = R.bo(st, "last_ok");
        c.lastMessage = R.sz(st, "last_message");
        c.lastBills = R.lo(st, "last_bills");
        c.lastFullAt = R.t(st, "last_full_at");
        v.compare = c;
        v.openAlerts = changes.openAlertCount(cu);
        return v;
    }

    @Override
    public List<String> customers(String q) {
        CurrentUser cu = operator();
        String text = Util.trim(q);
        return mapper.customers(text.isEmpty() ? null : Util.likeAny(text), cu.readFactories(null));
    }

    // ================================================================== 其他服务用的订单视图

    public Map<String, Object> snapshotOrg(String billNo) {
        return mapper.snapshotOrg(billNo);
    }

    /** 单据的生产组织（每张单据只有一个）：快照中的；已离开快照的取已出号单据台账。 */
    public Set<String> billOrgs(Collection<String> billNos) {
        Set<String> nos = new TreeSet<>();
        for (String b : billNos) {
            if (b != null && !b.isEmpty()) {
                nos.add(b);
            }
        }
        if (nos.isEmpty()) {
            return new HashSet<>();
        }
        Map<String, String> found = new HashMap<>();
        for (Map<String, Object> r : mapper.billOrgsSnap(nos)) {
            found.put(R.s(r, "bill_no"), R.s(r, "org_code"));
        }
        List<String> gone = nos.stream().filter(n -> !found.containsKey(n)).toList();
        if (!gone.isEmpty()) {
            for (Map<String, Object> r : mapper.billOrgsLedger(gone)) {
                found.put(R.s(r, "bill_no"), R.s(r, "org_code"));
            }
        }
        Set<String> out = new HashSet<>();
        for (String n : nos) {
            String o = found.get(n);
            out.add(o == null || o.isEmpty() ? "-" : o);
        }
        return out;
    }

    public String customerOfPi(String pi) {
        return mapper.customerOfPi(pi);
    }

    @Override
    public List<OrderTargetVO> targets(String q, String pi, Integer limit) {
        operator();
        int lim = Math.min(Math.max(limit == null ? 50 : limit, 1), 200);
        String text = Util.trim(q);
        List<Map<String, Object>> rows =
                mapper.targets(
                        Util.trimOrNull(pi), text.isEmpty() ? null : Util.likeAny(text), lim);
        Map<String, Map<String, Object>> lens = lineLengths(rows);
        Map<String, Map<String, Object>> fills = lineAttaches(rows);
        List<OrderTargetVO> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            OrderTargetVO v = new OrderTargetVO();
            v.orgCode = R.s(r, "org_code");
            v.orgName = R.s(r, "org_name");
            v.customerCode = R.s(r, "customer_code");
            v.pi = R.s(r, "pi");
            v.billNo = R.s(r, "bill_no");
            v.seq = R.i(r, "seq");
            v.materialCode = R.s(r, "material_code");
            v.materialName = R.s(r, "material_name");
            v.attachNo = effectiveAttach(r, fills.get(R.key(r))).value();
            v.length = effectiveLength(r, lens.get(R.key(r))).value();
            out.add(v);
        }
        return out;
    }

    // ================================================================== 提醒

    @Override
    public PageVO<OrderAlertVO> alerts(
            String status, String billNo, String alertType, Integer page, Integer pageSize) {
        return changes.listAlerts(
                operator(), status, billNo, alertType, PageReq.of(page, pageSize));
    }

    @Override
    public Map<String, Object> handleAlert(long id, String action, String note, Integer targetSeq) {
        return changes.handle(operator(), id, action, note, targetSeq);
    }
}
