package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.integration.K3GatewayPort;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.mapper.ChangeMapper;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.BillStateVO;
import com.adtek.sn.vo.OrderAlertVO;
import com.adtek.sn.vo.PageVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 订单变更处理（F-06）与单据暂停交付（原系统 app/services/changes.py）。
 *
 * <p>台账：单据第一次生成号时建立，记最近一次在快照中时的 PI、客户、生产组织和各物料行数量；表头只在总部「确认变更」时改为新值。每次同步后对 变动的已出号单据比对：PI / 生产组织 /
 * 客户变了、记录过已生成数的物料行失去对应 → 提醒并暂停该单未交付的号（CHANGE），PI 变了另冻结生成 和分配；数量下调到本单生成数以下、附件项次金蝶后来有值 →
 * 只提示；单据离开快照按单号查一次：不是已审核 → 反审核中（REVERSAL）暂停，查无此单 → 不暂停（已暂停的解除）。暂停只作用于未交付、未入批的号。
 */
@Service
public class ChangeServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(ChangeServiceImpl.class);
    public static final String PI = "PI";
    public static final String ORG = "ORG";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String QTY = "QTY";
    public static final String MATERIAL = "MATERIAL";
    public static final String ATTACH = "ATTACH";
    public static final String REVERSAL = "REVERSAL";
    public static final String DELETED = "DELETED";
    public static final List<String> BLOCKING = List.of(PI, ORG, CUSTOMER, MATERIAL);
    public static final String CONFIRM = "CONFIRM";
    public static final String MAP = "MAP";
    public static final String UNRELATED = "UNRELATED";
    public static final String VOID = "VOID";
    public static final String ACK = "ACK";
    public static final String AUTO = "AUTO";
    static final Map<String, List<String>> ALLOWED =
            Map.of(
                    PI, List.of(CONFIRM),
                    ORG, List.of(CONFIRM),
                    CUSTOMER, List.of(CONFIRM),
                    MATERIAL, List.of(MAP, UNRELATED),
                    QTY, List.of(ACK),
                    ATTACH, List.of(ACK),
                    REVERSAL, List.of(VOID),
                    DELETED, List.of(ACK));

    private final ChangeMapper mapper;
    private final OrderServiceImpl orders;
    private final K3ServiceImpl k3;
    private final BillStatsServiceImpl billStats;
    private final AuditServiceImpl audit;
    private final DeliveryServiceImpl delivery;
    private final TransferServiceImpl transfers;
    private final SnSettings settings;
    private final Tx tx;
    private final ThreadLocal<Set<String>> kicks = ThreadLocal.withInitial(LinkedHashSet::new);

    public ChangeServiceImpl(
            ChangeMapper mapper,
            @Lazy OrderServiceImpl orders,
            K3ServiceImpl k3,
            BillStatsServiceImpl billStats,
            AuditServiceImpl audit,
            @Lazy DeliveryServiceImpl delivery,
            @Lazy TransferServiceImpl transfers,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.orders = orders;
        this.k3 = k3;
        this.billStats = billStats;
        this.audit = audit;
        this.delivery = delivery;
        this.transfers = transfers;
        this.settings = settings;
        this.tx = tx;
    }

    // ================================================================== 计数 / 状态

    public void countDaily(String kind, String factory) {
        mapper.countDaily(Util.today(settings.zone()), kind, factory == null ? "" : factory);
    }

    public Map<String, BillStateVO> billStates(Collection<String> bills) {
        Map<String, BillStateVO> out = new HashMap<>();
        if (bills.isEmpty()) {
            return out;
        }
        Map<String, Long> alerts = new HashMap<>();
        for (Map<String, Object> r : mapper.openAlertsByBill(bills)) {
            alerts.put(R.s(r, "bill_no"), R.l(r, "n"));
        }
        for (Map<String, Object> r : mapper.ledgersOf(bills)) {
            BillStateVO v = new BillStateVO();
            v.suspend = R.s(r, "suspend");
            v.leaveState = R.s(r, "leave_state");
            v.piFrozen = R.b(r, "pi_frozen");
            v.openAlerts = alerts.getOrDefault(R.s(r, "bill_no"), 0L);
            v.issued = true;
            out.put(R.s(r, "bill_no"), v);
        }
        alerts.forEach(
                (b, n) -> {
                    if (!out.containsKey(b)) {
                        BillStateVO v = BillStateVO.empty();
                        v.openAlerts = n;
                        out.put(b, v);
                    }
                });
        return out;
    }

    public Map<String, Object> ledger(String billNo) {
        return mapper.ledger(billNo);
    }

    public Map<String, Object> ledgerLock(String billNo) {
        return mapper.ledgerLock(billNo);
    }

    /** 该单不能生成的原因：PI 变更未确认（冻结）/ 有未处理的「失去对应」；没有返回 null。 */
    public String generationBlock(String billNo) {
        Map<String, Object> l = ledger(billNo);
        if (l != null && R.b(l, "pi_frozen")) {
            return "PI_FROZEN";
        }
        if (mapper.openAlertOfType(billNo, MATERIAL)) {
            return "MATERIAL_LOST";
        }
        return null;
    }

    /** 该单不能分配的原因：PI 冻结 / 反审核中 / 暂停交付。 */
    public String allocationBlock(String billNo) {
        Map<String, Object> l = ledger(billNo);
        if (l == null) {
            return null;
        }
        if (R.b(l, "pi_frozen")) {
            return "PI_FROZEN";
        }
        if (REVERSAL.equals(R.s(l, "leave_state"))) {
            return "REVERSAL";
        }
        if (!Util.blank(R.s(l, "suspend"))) {
            return "SUSPENDED";
        }
        return null;
    }

    public long openAlertCount(CurrentUser cu) {
        return mapper.openAlertCount(cu.readFactories(null));
    }

    // ================================================================== 台账

    /** 单据第一次生成号时建立台账（生成事务内调用）。 */
    public void ensureLedger(String billNo) {
        if (ledger(billNo) != null) {
            return;
        }
        List<Map<String, Object>> rows = orders.lines(billNo);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> h = rows.getFirst();
        mapper.insertLedger(
                billNo,
                R.s(h, "pi"),
                R.s(h, "customer_code"),
                R.s(h, "org_code"),
                R.s(h, "org_name"),
                Util.now());
        mapper.insertLedgerLines(rows);
    }

    // ================================================================== 暂停交付

    private int setItemsSuspend(String billNo, String reason) {
        int total = mapper.setItemsSuspend(billNo, reason, Util.now());
        int cleared = mapper.clearDeliveredSuspend(billNo);
        if (total > 0 || cleared > 0) {
            billStats.touchBill(billNo);
        }
        return total;
    }

    /** 号的单据或状态变化后，按单据台账重算号上的暂停标记（quick：生成、分配，在 PI 操作锁内）。 */
    public void refreshItems(Collection<String> billNos, boolean quick) {
        for (String no : new TreeSet<>(billNos)) {
            if (!quick) {
                Map<String, Object> l = ledger(no);
                setItemsSuspend(no, l == null ? null : R.s(l, "suspend"));
                continue;
            }
            String reason = lockLedgerSuspend(no);
            if (reason == null && !mapper.anySuspended(no)) {
                continue;
            }
            setItemsSuspend(no, reason);
        }
    }

    /** 单据台账的暂停原因（当前值，加共享锁到事务结束）；没有台账为 null。 */
    public String lockLedgerSuspend(String billNo) {
        if (!mapper.ledgerExists(billNo)) {
            return null;
        }
        return mapper.ledgerSuspendShare(billNo);
    }

    private void applySuspend(CurrentUser cu, String billNo, String reason) {
        Map<String, Object> l = ledgerLock(billNo);
        if (l == null || Objects.equals(R.s(l, "suspend"), reason)) {
            if (l != null) {
                setItemsSuspend(billNo, reason);
            }
            return;
        }
        mapper.setLedgerSuspend(billNo, reason, Util.now());
        int n = setItemsSuspend(billNo, reason);
        String prev = R.s(l, "suspend");
        audit.record(
                cu,
                AuditEntryDTO.of(
                                reason == null
                                        ? AuditAction.ORDER_RESUME
                                        : AuditAction.ORDER_SUSPEND)
                        .bill(billNo)
                        .pi(R.s(l, "pi"))
                        .factory(R.s(l, "org_code"))
                        .count(n)
                        .info(
                                "suspend "
                                        + (Util.blank(prev) ? "-" : prev)
                                        + " -> "
                                        + (reason == null ? "-" : reason)));
        if (reason == null) {
            kicks.get().add(billNo);
        }
    }

    /** 事务提交后：本线程处理中解除暂停的单据启动推送。 */
    public void kickResumed() {
        List<String> bills = new ArrayList<>(new TreeSet<>(kicks.get()));
        kicks.get().clear();
        for (String no : bills) {
            try {
                delivery.kickBill(no);
            } catch (RuntimeException e) {
                log.error("kick_after_resume_failed bill={}", no, e);
            }
        }
    }

    private void reevaluate(CurrentUser cu, String billNo) {
        Map<String, Object> l = ledgerLock(billNo);
        if (l == null) {
            return;
        }
        Set<String> openTypes = new HashSet<>(mapper.openTypes(billNo));
        int frozen = openTypes.contains(PI) ? 1 : 0;
        if (frozen != R.i(l, "pi_frozen")) {
            mapper.setPiFrozen(billNo, frozen);
        }
        String reason;
        if (REVERSAL.equals(R.s(l, "leave_state"))) {
            reason = Util.SUSPEND_REVERSAL;
        } else if (openTypes.stream().anyMatch(BLOCKING::contains)) {
            reason = Util.SUSPEND_CHANGE;
        } else {
            reason = null;
        }
        applySuspend(cu, billNo, reason);
    }

    // ================================================================== 提醒

    private void alert(
            CurrentUser cu, String billNo, String typ, Integer seq, String before, String after) {
        Map<String, Object> cur = mapper.openAlertLock(billNo, typ, seq);
        LocalDateTime now = Util.now();
        if (cur != null) {
            if (!Objects.equals(R.s(cur, "after_value"), after)) {
                mapper.updateAlertAfter(R.l(cur, "id"), Util.cut(after, 255), now);
            }
            return;
        }
        mapper.insertAlert(billNo, typ, seq, Util.cut(before, 255), Util.cut(after, 255), now);
        Map<String, Object> l = ledger(billNo);
        audit.record(
                cu,
                AuditEntryDTO.of(AuditAction.ORDER_ALERT)
                        .bill(billNo)
                        .pi(l == null ? null : R.s(l, "pi"))
                        .factory(l == null ? null : R.s(l, "org_code"))
                        .info(
                                "type="
                                        + typ
                                        + " seq="
                                        + (seq != null ? seq.toString() : "-")
                                        + " "
                                        + before
                                        + " -> "
                                        + after));
    }

    private record Keep(String type, Integer seq) {}

    private void closeAuto(
            CurrentUser cu, String billNo, Collection<String> types, String note, Set<Keep> keep) {
        for (Map<String, Object> a : mapper.openAlertsOfTypes(billNo, types)) {
            if (keep != null && keep.contains(new Keep(R.s(a, "alert_type"), R.io(a, "seq")))) {
                continue;
            }
            close(cu, R.l(a, "id"), AUTO, note);
        }
    }

    private void close(CurrentUser cu, long alertId, String action, String note) {
        mapper.closeAlert(alertId, cu.empNo(), Util.now(), action, Util.cut(note, 500));
    }

    // ================================================================== 同步后的比对

    /** 同步替换 / 移出单据后：附件项次金蝶已有值提示；已出号单据的变更比对。返回提示类型列表。 */
    public List<String> afterSync(CurrentUser cu, List<String> present, List<String> removed) {
        List<String> notes = new ArrayList<>();
        for (String no : present) {
            try {
                notes.addAll(
                        tx.run(
                                () -> {
                                    List<String> n = attachK3(cu, no);
                                    if (ledger(no) != null) {
                                        onPresent(cu, no);
                                    }
                                    return n;
                                }));
                kickResumed();
            } catch (RuntimeException e) {
                kicks.get().clear();
                log.error("order_change_compare_failed bill={}", no, e);
            }
        }
        for (String no : removed) {
            try {
                afterRemoved(cu, no, null);
            } catch (RuntimeException e) {
                log.error("order_change_remove_failed bill={}", no, e);
            }
        }
        return notes;
    }

    private List<String> attachK3(CurrentUser cu, String billNo) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.attachK3Rows(billNo)) {
            mapper.updateAttachK3(billNo, R.i(r, "seq"), R.s(r, "attach_no"), Util.now());
            alert(cu, billNo, ATTACH, R.i(r, "seq"), R.s(r, "manual_value"), R.s(r, "attach_no"));
            out.add(ATTACH);
        }
        return out;
    }

    /** 单据在快照中：与台账比对，生成 / 更新 / 关闭提醒，重算暂停。 */
    void onPresent(CurrentUser cu, String billNo) {
        Map<String, Object> l = ledgerLock(billNo);
        List<Map<String, Object>> rows = orders.lines(billNo);
        if (l == null || rows.isEmpty()) {
            return;
        }
        Map<String, Object> h = rows.getFirst();
        Set<Keep> keep = new HashSet<>();
        String[][] heads = {
            {PI, R.s(l, "pi"), R.s(h, "pi")},
            {ORG, R.s(l, "org_code"), R.s(h, "org_code")},
            {CUSTOMER, R.s(l, "customer_code"), R.s(h, "customer_code")}
        };
        for (String[] t : heads) {
            if (!Objects.equals(t[1], t[2])) {
                String label =
                        ORG.equals(t[0])
                                ? (Util.nz(t[2]) + " " + R.sz(h, "org_name")).strip()
                                : t[2];
                String before =
                        ORG.equals(t[0])
                                ? (Util.nz(t[1]) + " " + R.sz(l, "org_name")).strip()
                                : t[1];
                alert(cu, billNo, t[0], null, before, label);
                keep.add(new Keep(t[0], null));
            }
        }
        Map<Integer, Map<String, Object>> bySeq = new HashMap<>();
        for (Map<String, Object> r : rows) {
            bySeq.put(R.i(r, "seq"), r);
        }
        List<Map<String, Object>> counts = mapper.countsOfBill(billNo);
        for (Map<String, Object> c : counts) {
            if (!(R.l(c, "gen_count") > 0 || R.l(c, "own_generated") > 0)) {
                continue;
            }
            Map<String, Object> line = bySeq.get(R.i(c, "seq"));
            if (line == null
                    || !Objects.equals(R.s(line, "material_code"), R.s(c, "material_code"))) {
                String cm = R.sz(c, "material_code");
                String lm = line == null ? null : R.sz(line, "material_code");
                alert(
                        cu,
                        billNo,
                        MATERIAL,
                        R.i(c, "seq"),
                        cm.isEmpty() ? "(空)" : cm,
                        line == null ? "(删除)" : (lm.isEmpty() ? "(空)" : lm));
                keep.add(new Keep(MATERIAL, R.i(c, "seq")));
            }
        }
        closeAuto(cu, billNo, BLOCKING, "比对已无差异", keep);
        Map<Integer, BigDecimal> oldQty = new HashMap<>();
        for (Map<String, Object> r : mapper.ledgerLines(billNo)) {
            oldQty.put(R.i(r, "seq"), R.d(r, "qty"));
        }
        Map<Integer, Long> own = new HashMap<>();
        for (Map<String, Object> c : counts) {
            own.put(R.i(c, "seq"), R.l(c, "own_generated"));
        }
        for (Map<String, Object> r : rows) {
            int seq = R.i(r, "seq");
            long q = OrderServiceImpl.snQty(r);
            BigDecimal old = oldQty.get(seq);
            if (old != null && old.compareTo(R.d(r, "qty")) != 0 && q < own.getOrDefault(seq, 0L)) {
                long o = own.get(seq);
                alert(
                        cu,
                        billNo,
                        QTY,
                        seq,
                        old.toBigInteger().toString(),
                        q + "（本单已生成 " + o + "，多出 " + (o - q) + "）");
            }
        }
        mapper.upsertLedgerLines(rows);
        if (R.s(l, "leave_state") != null || R.t(l, "left_at") != null) {
            if (R.s(l, "leave_state") != null) {
                closeAuto(cu, billNo, List.of(REVERSAL), "重新审核回到快照", null);
            }
            mapper.clearLeave(billNo, Util.now());
        }
        reevaluate(cu, billNo);
    }

    /** 已出号单据离开快照：按单号查一次（view 已查过时直接用），按结果暂停或解除。 */
    public void afterRemoved(CurrentUser cu, String billNo, K3GatewayPort.BillView view) {
        if (ledger(billNo) == null) {
            return;
        }
        K3GatewayPort.BillView v = view;
        if (v == null) {
            try {
                v = k3.viewBill(billNo);
            } catch (BusinessException e) {
                log.warn("bill_view_failed bill={} err={}", billNo, Biz.message(e));
                v = null;
            }
        }
        K3GatewayPort.BillView fv = v;
        try {
            tx.exec(() -> onAbsent(cu, billNo, fv));
        } catch (RuntimeException e) {
            kicks.get().clear();
            throw e;
        }
        kickResumed();
    }

    private void onAbsent(CurrentUser cu, String billNo, K3GatewayPort.BillView view) {
        Map<String, Object> l = ledgerLock(billNo);
        if (l == null) {
            return;
        }
        LocalDateTime now = Util.now();
        if (view != null && view.found() && "C".equals(view.docStatus())) {
            orders.replaceBills(Map.of(billNo, view.rows()), now);
            onPresent(cu, billNo);
            return;
        }
        mapper.setLeftAt(billNo, now);
        String leave = R.s(l, "leave_state");
        if (view != null && !view.found()) {
            if (DELETED.equals(leave)) {
                return;
            }
            mapper.setLeaveState(billNo, DELETED);
            List<String> types = new ArrayList<>(BLOCKING);
            types.add(REVERSAL);
            closeAuto(cu, billNo, types, "单据已在金蝶删除", null);
            alert(cu, billNo, DELETED, null, "", "查无此单");
            reevaluate(cu, billNo);
            return;
        }
        if ("VOID_CONFIRMED".equals(leave)) {
            return;
        }
        String status =
                view == null ? "?" : (Util.blank(view.docStatus()) ? "?" : view.docStatus());
        if (!REVERSAL.equals(leave)) {
            mapper.setLeaveState(billNo, REVERSAL);
        }
        alert(cu, billNo, REVERSAL, null, "C", status);
        reevaluate(cu, billNo);
    }

    /** 比对同步周期内：反审核中的已出号单据按单号复查。 */
    public void recheckReversed(CurrentUser cu, Set<String> approved) {
        for (String no : mapper.reversedBills()) {
            if (approved.contains(no)) {
                continue;
            }
            try {
                afterRemoved(cu, no, null);
            } catch (RuntimeException e) {
                log.error("reversal_recheck_failed bill={}", no, e);
            }
        }
    }

    /** 全量重建后对全部已出号单据比对。 */
    public void afterRebuild(CurrentUser cu) {
        Set<String> inSnap = new HashSet<>(mapper.snapBills());
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (Map<String, Object> l : mapper.allLedgers()) {
            String no = R.s(l, "bill_no");
            if (inSnap.contains(no)) {
                present.add(no);
            } else if (!"VOID_CONFIRMED".equals(R.s(l, "leave_state"))
                    && !DELETED.equals(R.s(l, "leave_state"))) {
                absent.add(no);
            }
        }
        afterSync(cu, present, absent);
    }

    // ================================================================== 提醒列表与处理

    public PageVO<OrderAlertVO> listAlerts(
            CurrentUser cu, String status, String billNo, String typ, PageReq page) {
        String st = Util.trim(status);
        if (st.isEmpty()) {
            st = "OPEN";
        }
        String s = "ALL".equals(st) ? null : st;
        String b = Util.trimOrNull(billNo);
        String t = Util.trimOrNull(typ);
        List<String> scope = cu.readFactories(null);
        long total = mapper.countAlerts(s, b, t, scope);
        List<Map<String, Object>> rows =
                mapper.pageAlerts(s, b, t, scope, page.offset(), page.size());
        Map<String, long[]> counts = billCounts(rows.stream().map(r -> R.s(r, "bill_no")).toList());
        List<OrderAlertVO> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long[] c = counts.getOrDefault(R.s(r, "bill_no"), new long[3]);
            OrderAlertVO v = new OrderAlertVO();
            v.id = R.l(r, "id");
            v.billNo = R.s(r, "bill_no");
            v.alertType = R.s(r, "alert_type");
            v.seq = R.io(r, "seq");
            v.beforeValue = R.s(r, "before_value");
            v.afterValue = R.s(r, "after_value");
            v.status = R.s(r, "status");
            v.createdAt = R.t(r, "created_at");
            v.closedBy = R.s(r, "closed_by");
            v.closedAt = R.t(r, "closed_at");
            v.closeAction = R.s(r, "close_action");
            v.note = R.s(r, "note");
            v.pi = R.s(r, "pi");
            v.orgCode = R.s(r, "org_code");
            v.orgName = R.s(r, "org_name");
            v.suspend = R.s(r, "suspend");
            v.leaveState = R.s(r, "leave_state");
            v.suspendedQty = c[0];
            v.deliveredQty = c[1];
            v.inOrgQty = c[2];
            v.actions =
                    "OPEN".equals(v.status)
                            ? ALLOWED.getOrDefault(v.alertType, List.of())
                            : List.of();
            items.add(v);
        }
        return PageVO.of(items, total, page.page(), page.size());
    }

    private Map<String, long[]> billCounts(List<String> bills) {
        Map<String, long[]> out = new HashMap<>();
        if (bills.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : mapper.billItemCounts(new TreeSet<>(bills))) {
            out.put(R.s(r, "bill_no"), new long[] {R.l(r, "s"), R.l(r, "d"), R.l(r, "a")});
        }
        return out;
    }

    private void checkHandleScope(CurrentUser cu, Map<String, Object> a) {
        if (cu.isAdmin()) {
            return;
        }
        Map<String, Object> led = ledger(R.s(a, "bill_no"));
        Set<String> orgs = new LinkedHashSet<>();
        String lo = led == null ? null : R.s(led, "org_code");
        orgs.add(Util.blank(lo) ? "-" : lo);
        orgs.addAll(mapper.itemFactoriesOfBill(R.s(a, "bill_no")));
        if (ORG.equals(R.s(a, "alert_type"))) {
            orgs.add(R.s(a, "before_value"));
            orgs.add(R.s(a, "after_value"));
        }
        cu.checkWrite(orgs.toArray(String[]::new));
    }

    /** 处理提醒：确认变更 / 确认对应 / 确认无关 / 确认已作废（须填原因）/ 知道了。 */
    public Map<String, Object> handle(
            CurrentUser cu, long alertId, String action, String note, Integer targetSeq) {
        String act = Util.trim(action);
        Map<String, Object> a = mapper.alert(alertId);
        if (a == null || !"OPEN".equals(R.s(a, "status"))) {
            throw Biz.err(SnErrorCode.ALERT_NOT_FOUND);
        }
        if (!ALLOWED.getOrDefault(R.s(a, "alert_type"), List.of()).contains(act)) {
            throw Biz.err(SnErrorCode.ALERT_ACTION_INVALID);
        }
        checkHandleScope(cu, a);
        String why = Util.trim(note);
        if (VOID.equals(act) && (why.isEmpty() || why.length() > 500)) {
            throw Biz.err(SnErrorCode.REASON_REQUIRED);
        }
        if (MAP.equals(act)) {
            transfers.mapMaterial(cu, a, targetSeq, why);
        } else {
            try {
                tx.exec(
                        () -> {
                            Map<String, Object> x = mapper.alertLock(alertId);
                            if (x == null || !"OPEN".equals(R.s(x, "status"))) {
                                throw Biz.err(SnErrorCode.ALERT_NOT_FOUND);
                            }
                            String no = R.s(x, "bill_no");
                            String type = R.s(x, "alert_type");
                            if (CONFIRM.equals(act)) {
                                Map<String, Object> snap = mapper.snapFirst(no);
                                if (PI.equals(type)) {
                                    mapper.updateLedgerPi(
                                            no,
                                            snap != null ? R.s(snap, "pi") : R.s(x, "after_value"),
                                            Util.now());
                                } else if (CUSTOMER.equals(type)) {
                                    mapper.updateLedgerCustomer(
                                            no,
                                            snap != null
                                                    ? R.s(snap, "customer_code")
                                                    : R.s(x, "after_value"),
                                            Util.now());
                                } else if (snap != null) {
                                    mapper.updateLedgerOrg(
                                            no,
                                            R.s(snap, "org_code"),
                                            R.s(snap, "org_name"),
                                            Util.now());
                                }
                            } else if (VOID.equals(act)) {
                                mapper.setVoidConfirmed(no, Util.now());
                                closeAuto(cu, no, BLOCKING, "单据已确认作废", null);
                            }
                            close(cu, R.l(x, "id"), act, why.isEmpty() ? null : why);
                            Map<String, Object> l = ledger(no);
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.ORDER_ALERT_HANDLE)
                                            .bill(no)
                                            .pi(l == null ? null : R.s(l, "pi"))
                                            .factory(l == null ? null : R.s(l, "org_code"))
                                            .why(why.isEmpty() ? null : why)
                                            .info(
                                                    "alert="
                                                            + R.l(x, "id")
                                                            + " type="
                                                            + type
                                                            + " action="
                                                            + act));
                            reevaluate(cu, no);
                        });
            } catch (RuntimeException e) {
                kicks.get().clear();
                throw e;
            }
            kickResumed();
        }
        return R.of("id", alertId, "action", act);
    }

    /** 确认对应完成后（在转移服务的事务内）关闭提醒并重算。 */
    public void closeMaterialAlert(CurrentUser cu, long alertId, String note) {
        Map<String, Object> a = mapper.alert(alertId);
        close(cu, alertId, MAP, note);
        reevaluate(cu, R.s(a, "bill_no"));
    }
}
