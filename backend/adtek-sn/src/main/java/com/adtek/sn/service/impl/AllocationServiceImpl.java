package com.adtek.sn.service.impl;

import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.domain.FactoryEntity;
import com.adtek.sn.dto.AllocateDTO;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.dto.LineRefDTO;
import com.adtek.sn.mapper.AllocationMapper;
import com.adtek.sn.service.AllocationService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.Camel;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.DbErrors;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnLocks;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.PushRemainingVO;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * SN 分配与分配页详情（原系统 allocation.py）。号只能分到订单的生产组织（快照中订单的生产组织；订单已不在快照时取号上的生成时订单生产组织； 反审核中、PI
 * 变更待确认、暂停交付的单据跳过）。对接 MES 的改为待推送（未入批）并自动开始推送；未对接的改为已分配。分配与生成、撤销共用 PI 操作锁。
 */
@Service
public class AllocationServiceImpl implements AllocationService {
    public static final String TO_ALLOCATE = "TO_ALLOCATE";
    public static final String ALLOCATED = "ALLOCATED";
    public static final String BILL_PENDING = "PENDING";
    public static final String BILL_PROCESSING = "PROCESSING";
    public static final String BILL_ALLOCATED = "ALLOCATED";
    private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "WAITING");

    private final AllocationMapper mapper;
    private final ChangeServiceImpl changes;
    private final OrderServiceImpl orders;
    private final FactoryServiceImpl factories;
    private final PushServiceImpl push;
    private final WorkbenchServiceImpl workbench;
    private final BillStatsServiceImpl billStats;
    private final AuditServiceImpl audit;
    private final SnLocks locks;
    private final SnSettings settings;
    private final Tx tx;

    public AllocationServiceImpl(
            AllocationMapper mapper,
            ChangeServiceImpl changes,
            OrderServiceImpl orders,
            FactoryServiceImpl factories,
            PushServiceImpl push,
            WorkbenchServiceImpl workbench,
            BillStatsServiceImpl billStats,
            AuditServiceImpl audit,
            SnLocks locks,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.changes = changes;
        this.orders = orders;
        this.factories = factories;
        this.push = push;
        this.workbench = workbench;
        this.billStats = billStats;
        this.audit = audit;
        this.locks = locks;
        this.settings = settings;
        this.tx = tx;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo()).requireOperator();
    }

    /** 分配到哪个生产组织：快照中订单的生产组织（不在快照为 null）；以及不能分配的原因。 */
    private record Target(String org, String block) {}

    private Target targetOrg(String billNo) {
        String block = changes.allocationBlock(billNo);
        if (block != null) {
            return new Target(null, block);
        }
        Map<String, Object> so = orders.snapshotOrg(billNo);
        return new Target(so == null ? null : R.s(so, "org_code"), null);
    }

    /** 规范化范围：物料行优先，其次单据，都没有为整张 PI。 */
    private record Range(List<String> bills, List<LineRefDTO> lines) {}

    private static Range range(List<String> billsIn, List<LineRefDTO> linesIn) {
        if (linesIn != null && !linesIn.isEmpty()) {
            List<LineRefDTO> lines = new ArrayList<>();
            for (LineRefDTO x : linesIn) {
                LineRefDTO l = new LineRefDTO();
                l.billNo = Util.trim(x.billNo);
                l.seq = x.seq == null ? 0 : x.seq;
                lines.add(l);
            }
            return new Range(null, lines);
        }
        List<String> nos = new ArrayList<>();
        if (billsIn != null) {
            for (String b : billsIn) {
                if (!Util.trim(b).isEmpty()) {
                    nos.add(Util.trim(b));
                }
            }
        }
        return new Range(nos.isEmpty() ? null : nos, null);
    }

    private void checkPicked(CurrentUser cu, List<String> billsIn, List<LineRefDTO> linesIn) {
        List<String> picked = new ArrayList<>();
        if (linesIn != null && !linesIn.isEmpty()) {
            linesIn.forEach(x -> picked.add(Util.trim(x.billNo)));
        } else if (billsIn != null) {
            billsIn.forEach(b -> picked.add(Util.trim(b)));
        }
        if (!picked.isEmpty()) {
            cu.checkWrite(orders.billOrgs(picked).toArray(String[]::new));
        }
    }

    private record Group(String billNo, String org, String genOrg, long qty, boolean snapshot) {}

    private record Groups(List<Group> groups, List<Map<String, Object>> skipped) {}

    private Groups groups(String pi, Range rg, List<String> facs) {
        List<Group> groups = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Map<String, Object> r : mapper.groups(pi, rg.bills(), rg.lines())) {
            Target t = targetOrg(R.s(r, "bill_no"));
            String org = t.org() != null ? t.org() : R.s(r, "gen_org_code");
            if (facs != null && !facs.contains(org)) {
                continue;
            }
            if (t.block() != null) {
                skipped.add(
                        R.of("billNo", R.s(r, "bill_no"), "qty", R.l(r, "n"), "reason", t.block()));
                continue;
            }
            groups.add(
                    new Group(
                            R.s(r, "bill_no"),
                            org,
                            R.s(r, "gen_org_code"),
                            R.l(r, "n"),
                            t.org() != null));
        }
        return new Groups(groups, skipped);
    }

    /** 号已分配、待推送（未入批），但本组织本 PI 还没开始推送。 */
    boolean pushIdle(String org, Map<String, Object> task, PushRemainingVO rem) {
        return rem.unbatched > 0
                && rem.batches == 0
                && (task == null || "DONE".equals(task.get("state")))
                && factories.isMes(org);
    }

    /** 推送中断（由「继续推送」处理）。 */
    static boolean pushInterrupted(Map<String, Object> task, PushRemainingVO rem) {
        Object state = task == null ? null : task.get("state");
        return rem.pushes > 0
                && (state == null || !ACTIVE.contains(state.toString()))
                && (rem.batches > 0 || "STOPPED".equals(state));
    }

    private record Idle(String org, String billNo, long qty, List<String> materials) {}

    private List<Idle> idle(String pi, Range rg, List<String> facs) {
        Map<String, Boolean> idleOrg = new HashMap<>();
        List<Idle> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.idle(pi, rg.bills(), rg.lines())) {
            String org = R.s(r, "factory_code");
            if (facs != null && !facs.contains(org)) {
                continue;
            }
            boolean on =
                    idleOrg.computeIfAbsent(
                            org,
                            o -> pushIdle(o, push.taskState(o, pi), push.remainingPushes(o, pi)));
            if (on) {
                out.add(
                        new Idle(
                                org,
                                R.s(r, "bill_no"),
                                R.l(r, "n"),
                                Arrays.asList(R.sz(r, "m").split(",", -1))));
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> preview(AllocateDTO body) {
        CurrentUser cu = cu();
        String pi = Util.trim(body.piNo);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        checkPicked(cu, body.bills, body.lines);
        List<String> facs = cu.readFactories(null);
        Range rg = range(body.bills, body.lines);
        Groups gs = groups(pi, rg, facs);
        List<Idle> idle = idle(pi, rg, facs);
        Map<String, long[]> qty = new LinkedHashMap<>();
        Map<String, List<String>> bills = new HashMap<>();
        Map<String, Set<String>> materials = new HashMap<>();
        Map<String, Set<String>> orgsOfBill = new HashMap<>();
        for (Group g : gs.groups()) {
            qty.computeIfAbsent(g.org(), k -> new long[1])[0] += g.qty();
            bills.computeIfAbsent(g.org(), k -> new ArrayList<>()).add(g.billNo());
            materials.computeIfAbsent(g.org(), k -> new TreeSet<>());
            orgsOfBill.computeIfAbsent(g.billNo(), k -> new LinkedHashSet<>()).add(g.org());
        }
        for (Map<String, Object> r : mapper.pendingMaterials(pi, rg.bills(), rg.lines())) {
            for (String org : orgsOfBill.getOrDefault(R.s(r, "bill_no"), Set.of())) {
                materials.get(org).addAll(Arrays.asList(R.sz(r, "m").split(",", -1)));
            }
        }
        long requeue = 0;
        for (Idle x : idle) {
            qty.computeIfAbsent(x.org(), k -> new long[1])[0] += x.qty();
            bills.computeIfAbsent(x.org(), k -> new ArrayList<>()).add(x.billNo());
            materials.computeIfAbsent(x.org(), k -> new TreeSet<>()).addAll(x.materials());
            requeue += x.qty();
        }
        Map<String, String> names = factories.names();
        TreeMap<String, Map<String, Object>> outOrgs = new TreeMap<>();
        long pushes = 0;
        long total = 0;
        for (Map.Entry<String, long[]> e : qty.entrySet()) {
            String org = e.getKey();
            long n = e.getValue()[0];
            FactoryEntity f = factories.get(org);
            boolean mes = f != null && f.mesEnabled;
            long nPush = mes ? (n + Math.max(1, f.batchSize) - 1) / Math.max(1, f.batchSize) : 0;
            pushes += nPush;
            total += n;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("orgCode", org);
            o.put("orgName", names.getOrDefault(org, ""));
            o.put("mesEnabled", mes);
            o.put("qty", n);
            o.put("pushes", nPush);
            o.put("bills", new ArrayList<>(new TreeSet<>(bills.get(org))));
            o.put(
                    "materials",
                    new ArrayList<>(new TreeSet<>(materials.getOrDefault(org, Set.of()))));
            outOrgs.put(org, o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("piNo", pi);
        out.put("qty", total);
        out.put("pushes", pushes);
        out.put("orgs", new ArrayList<>(outOrgs.values()));
        out.put("skipped", gs.skipped());
        out.put("requeue", requeue);
        return out;
    }

    private record AllocResult(
            long total, Set<String> started, Map<String, Long> requeue, Groups groups) {}

    @Override
    public Map<String, Object> allocate(AllocateDTO body) {
        CurrentUser cu = cu();
        String pi = Util.trim(body.piNo);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        checkPicked(cu, body.bills, body.lines);
        Range rg = range(body.bills, body.lines);
        AllocResult res =
                locks.withPiLock(
                        () -> {
                            List<String> facs = cu.readFactories(null);
                            AllocResult r =
                                    DbErrors.retryDeadlock(
                                            () -> allocateTx(cu, pi, rg, facs),
                                            DbErrors.DEADLOCK_ATTEMPTS,
                                            false);
                            TreeSet<String> orgs = new TreeSet<>(r.started());
                            orgs.addAll(r.requeue().keySet());
                            for (String org : orgs) {
                                push.startTask(cu, org, pi);
                            }
                            return r;
                        },
                        pi);
        long requeue = res.requeue().values().stream().mapToLong(Long::longValue).sum();
        TreeSet<String> orgs = new TreeSet<>(res.requeue().keySet());
        res.groups().groups().forEach(g -> orgs.add(g.org()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("piNo", pi);
        out.put("qty", res.total() + requeue);
        out.put("requeue", requeue);
        out.put("skipped", res.groups().skipped());
        out.put("orgs", new ArrayList<>(orgs));
        return out;
    }

    private AllocResult allocateTx(CurrentUser cu, String pi, Range rg, List<String> facs) {
        return tx.run(
                () -> {
                    Groups gs = groups(pi, rg, facs);
                    List<Idle> idle = idle(pi, rg, facs);
                    if (gs.groups().isEmpty() && idle.isEmpty()) {
                        throw Biz.err(SnErrorCode.ALLOC_NOTHING);
                    }
                    var now = Util.now();
                    long total = 0;
                    Set<String> started = new TreeSet<>();
                    for (Group g : gs.groups()) {
                        boolean mes = factories.isMes(g.org());
                        String status = mes ? Util.TO_PUSH : Util.ALLOCATED;
                        String genOrg = g.snapshot() ? null : g.genOrg();
                        List<Map<String, Object>> perLine =
                                mapper.perLine(pi, g.billNo(), genOrg, rg.bills(), rg.lines());
                        if (perLine.isEmpty()) {
                            continue;
                        }
                        int n =
                                mapper.allocate(
                                        pi,
                                        g.billNo(),
                                        genOrg,
                                        status,
                                        g.org(),
                                        now,
                                        rg.bills(),
                                        rg.lines());
                        billStats.touch(pi, g.billNo());
                        String a = null;
                        String b = null;
                        for (Map<String, Object> pl : perLine) {
                            mapper.addAllocCount(
                                    g.billNo(), R.l(pl, "bill_seq"), R.l(pl, "n"), now);
                            String pa = R.s(pl, "a");
                            String pb = R.s(pl, "b");
                            a = a == null || pa.compareTo(a) < 0 ? pa : a;
                            b = b == null || pb.compareTo(b) > 0 ? pb : b;
                        }
                        audit.record(
                                cu,
                                AuditEntryDTO.of(AuditAction.SN_ALLOCATE)
                                        .factory(g.org())
                                        .pi(pi)
                                        .bill(g.billNo())
                                        .range(a, b)
                                        .count(n)
                                        .status(Util.PENDING_ALLOC, status)
                                        .info("mes=" + (mes ? 1 : 0)));
                        total += n;
                        if (mes) {
                            started.add(g.org());
                        }
                    }
                    Map<String, Long> requeue = new TreeMap<>();
                    for (Idle x : idle) {
                        requeue.merge(x.org(), x.qty(), Long::sum);
                    }
                    for (Map.Entry<String, Long> e : requeue.entrySet()) {
                        audit.record(
                                cu,
                                AuditEntryDTO.of(AuditAction.SN_PUSH_RESUME)
                                        .factory(e.getKey())
                                        .pi(pi)
                                        .count(e.getValue())
                                        .info("key=" + pi + " from=allocate"));
                    }
                    if (!gs.groups().isEmpty()) {
                        Set<String> nos = new TreeSet<>();
                        gs.groups().forEach(g -> nos.add(g.billNo()));
                        changes.refreshItems(nos, true);
                    }
                    return new AllocResult(total, started, requeue, gs);
                });
    }

    // ---------------------------------------------------------------- 左侧单据列表
    public static String billState(long pending, long toPush) {
        if (pending > 0) {
            return BILL_PENDING;
        }
        return toPush > 0 ? BILL_PROCESSING : BILL_ALLOCATED;
    }

    @Override
    public Map<String, Object> bills(String q, String tab, PageReq page) {
        CurrentUser cu = cu();
        billStats.refresh();
        List<String> facs = cu.readFactories(null);
        String text = Util.trim(q);
        String like = text.isEmpty() ? null : Util.likeAny(text);
        String t = Util.trim(tab);
        String tb = TO_ALLOCATE.equals(t) || ALLOCATED.equals(t) ? t : null;
        long total = mapper.countBills(facs, like, tb);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : mapper.pageBills(facs, like, tb, page.offset(), page.size())) {
            long pending = R.l(r, "pending");
            long toPush = R.l(r, "to_push");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("piNo", R.s(r, "pi_no"));
            m.put("billNo", R.s(r, "bill_no"));
            m.put("qty", R.l(r, "qty"));
            m.put("pendingAlloc", pending);
            m.put("toPush", toPush);
            m.put("suspended", R.l(r, "suspended"));
            m.put("state", billState(pending, toPush));
            m.put("handledAt", R.t(r, "handled_at"));
            items.add(m);
        }
        Map<String, Object> s = mapper.summary(facs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", page.page());
        out.put("pageSize", page.size());
        out.put("totalCapped", false);
        out.put(
                "summary",
                R.of("pendingAlloc", R.l(s, "pending"), "unbatched", R.l(s, "unbatched")));
        return out;
    }

    // ---------------------------------------------------------------- 分配页详情
    @Override
    public Map<String, Object> context(String piIn) {
        CurrentUser cu = cu();
        String pi = Util.trim(piIn);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        List<String> facs = cu.readFactories(null);
        List<Map<String, Object>> rows = mapper.contextRows(pi, facs);
        TreeSet<String> billNos = new TreeSet<>();
        rows.forEach(r -> billNos.add(R.s(r, "bill_no")));
        Map<String, Map<String, Object>> mo = new HashMap<>();
        if (!billNos.isEmpty()) {
            for (Map<String, Object> r : mapper.orderLines(billNos)) {
                mo.put(R.key(R.s(r, "bill_no"), R.i(r, "seq")), r);
            }
        }
        Map<String, String> names = factories.names();
        Map<String, Target> targets = new HashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        Map<String, long[]> states = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            long pending = R.l(r, "pending");
            String block = null;
            String org;
            String bill = R.s(r, "bill_no");
            if (pending > 0) {
                Target t = targets.computeIfAbsent(bill, this::targetOrg);
                block = t.block();
                org = block != null ? null : (t.org() != null ? t.org() : R.s(r, "gen_org"));
            } else {
                org = R.s(r, "org");
            }
            FactoryEntity f = org == null ? null : factories.get(org);
            Map<String, Object> k3 = mo.get(R.key(bill, R.i(r, "bill_seq")));
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("billNo", bill);
            x.put("seq", R.l(r, "bill_seq"));
            x.put("materialCode", R.s(r, "material_code"));
            x.put("attachNo", R.sz(r, "attach_no"));
            x.put("orderQty", k3 == null ? null : OrderServiceImpl.snQty(k3));
            x.put("customerPn", k3 == null ? "" : R.s(k3, "customer_pn"));
            x.put("startSeq", R.lo(r, "s"));
            x.put("endSeq", R.lo(r, "e"));
            x.put("qty", R.l(r, "n"));
            x.put("pendingAlloc", pending);
            x.put("toPush", R.l(r, "to_push"));
            x.put("failed", R.l(r, "failed"));
            x.put("startSn", R.s(r, "a"));
            x.put("endSn", R.s(r, "b"));
            x.put("orgCode", org);
            x.put("orgName", org == null ? "" : names.getOrDefault(org, ""));
            x.put("mesEnabled", f != null && f.mesEnabled);
            x.put("skip", block);
            x.put("imported", R.b(r, "imported"));
            lines.add(x);
            long[] st = states.computeIfAbsent(bill, k -> new long[2]);
            st[0] += pending;
            st[1] += R.l(r, "to_push");
        }
        for (Map<String, Object> x : lines) {
            long[] st = states.get((String) x.get("billNo"));
            x.put("billState", billState(st[0], st[1]));
        }
        Map<String, Long> stats =
                workbench.itemStats(List.of(pi), facs, false).getOrDefault(pi, Map.of());
        BigDecimal quota = mapper.quota(pi, facs);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedQty", workbench.genOwn(List.of(pi), facs).getOrDefault(pi, 0L));
        summary.put("transferredIn", stats.getOrDefault("transferred_in", 0L));
        summary.put("pendingAlloc", stats.getOrDefault("pending", 0L));
        summary.put("allocatedQty", stats.getOrDefault("allocated", 0L));
        summary.put("unbatched", stats.getOrDefault("unbatched", 0L));
        summary.put("suspended", stats.getOrDefault("suspended", 0L));
        summary.put("quota", quota == null ? 0L : quota.longValue());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("piNo", pi);
        String customer = orders.customerOfPi(pi);
        out.put("customerCode", customer == null ? "" : customer);
        out.put("summary", summary);
        out.put("lines", lines);
        out.put("push", pushStatus(pi, facs));
        return out;
    }

    /** 按生产组织：推送任务状态、「第 i / N 次」、是否在等 MES 回执、已推送枚数、剩余次数、失败原因。 */
    public List<Map<String, Object>> pushStatus(String pi, List<String> facs) {
        TreeSet<String> orgs = new TreeSet<>();
        for (String o : mapper.pushOrgs(pi)) {
            if (o != null && !o.isEmpty() && (facs == null || facs.contains(o))) {
                orgs.add(o);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String org : orgs) {
            FactoryEntity f = factories.get(org);
            boolean mes = f != null && f.mesEnabled;
            if (!mes && !mapper.hasBatch(org, pi)) {
                continue;
            }
            Map<String, Object> t = push.taskState(org, pi);
            PushRemainingVO rem = push.remainingPushes(org, pi);
            long pushed = mapper.pushedQty(org, pi);
            long done = mapper.doneBatches(org, pi);
            Map<String, Object> lastFail = mapper.lastProblem(org, pi);
            String current = null;
            if (t != null
                    && ("RUNNING".equals(t.get("state")) || "WAITING".equals(t.get("state")))
                    && t.get("currentBatch") != null) {
                current = mapper.currentPushState(String.valueOf(t.get("currentBatch")));
            }
            long total = done + rem.pushes;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("factoryCode", org);
            m.put("mesEnabled", mes);
            m.put("task", t);
            m.put("remaining", rem);
            m.put("doneBatches", done);
            m.put("currentPush", Math.min(total, done + (current != null ? 1 : 0)));
            m.put("waitingReceipt", "WAIT_RECEIPT".equals(current));
            m.put("totalBatches", total);
            m.put("pushedQty", pushed);
            m.put("lastProblem", lastFail == null ? null : Camel.map(lastFail));
            m.put("interrupted", mes && pushInterrupted(t, rem));
            m.put("idle", pushIdle(org, t, rem) ? rem.unbatched : 0);
            out.add(m);
        }
        return out;
    }
}
