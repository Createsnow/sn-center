package com.adtek.sn.service.impl;

import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.mapper.WorkbenchMapper;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.Camel;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.R;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.BillStateVO;
import com.adtek.sn.vo.GenLineVO;
import com.adtek.sn.vo.OrderLineVO;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SN 生成页与分配页共用的 PI 工作台（F-10）以及生成页详情（原系统 app/services/workbench.py）。
 *
 * <p>每张 PI 一行：客户、单据数、生产组织、状态、订单总数、已生成、转入号、已分配；行上按钮按 F-10 第 3 条判断。生成详情：单据 → 物料行
 * （规则、附件项次、米数、号段）、生成任务、空号段。
 */
@Service
public class WorkbenchServiceImpl {
    static final int LIST_LIMIT = 500;
    static final int DETAIL_ROWS = 60;
    public static final String GENERATING = "GENERATING";
    public static final String CANNOT = "CANNOT";
    public static final String NEED_RULE = "NEED_RULE";
    public static final String TO_GENERATE = "TO_GENERATE";
    public static final String PARTIAL = "PARTIAL";
    public static final String TO_ALLOCATE = "TO_ALLOCATE";
    public static final String PUSH_INTERRUPTED = "PUSH_INTERRUPTED";
    public static final String DONE = "DONE";
    public static final String BILL_PENDING = "PENDING";
    public static final String BILL_PROCESSING = "PROCESSING";
    public static final String BILL_ALLOCATED = "ALLOCATED";

    private final WorkbenchMapper mapper;
    private final OrderServiceImpl orders;
    private final GenerateServiceImpl generate;
    private final RuleServiceImpl rules;
    private final ChangeServiceImpl changes;

    public WorkbenchServiceImpl(
            WorkbenchMapper mapper,
            OrderServiceImpl orders,
            GenerateServiceImpl generate,
            RuleServiceImpl rules,
            ChangeServiceImpl changes) {
        this.mapper = mapper;
        this.orders = orders;
        this.generate = generate;
        this.rules = rules;
        this.changes = changes;
    }

    /** 单据状态：有待分配的号 = 待分配；号都已分配但还有待推送的 = 待处理；否则已分配。 */
    public static String billState(long pending, long toPush) {
        if (pending > 0) {
            return BILL_PENDING;
        }
        return toPush > 0 ? BILL_PROCESSING : BILL_ALLOCATED;
    }

    /** 各 PI 的号数统计；gen_only：只数本系统生成的号。 */
    public Map<String, Map<String, Long>> itemStats(
            Collection<String> pis, List<String> scope, boolean genOnly) {
        Map<String, Map<String, Long>> out = new HashMap<>();
        if (pis.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : mapper.itemStats(pis, scope, genOnly)) {
            Map<String, Long> m = new HashMap<>();
            for (String k :
                    List.of("pending", "allocated", "transferred_in", "unbatched", "suspended")) {
                m.put(k, R.l(r, k));
            }
            out.put(R.s(r, "pi_no"), m);
        }
        for (Map<String, Object> r : mapper.interrupted(pis, scope)) {
            out.computeIfAbsent(R.s(r, "pi_no"), k -> new HashMap<>())
                    .put("interrupted", R.l(r, "n"));
        }
        for (Map<String, Object> r : mapper.activeTasks(pis, scope)) {
            out.computeIfAbsent(R.s(r, "task_key"), k -> new HashMap<>()).put("tasks", R.l(r, "n"));
        }
        return out;
    }

    /** 已生成：本 PI 各订单的已生成数之和，按号上的生成 PI 计。 */
    public Map<String, Long> genOwn(Collection<String> pis, List<String> scope) {
        Map<String, Long> out = new HashMap<>();
        if (pis.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : mapper.genOwn(pis, scope)) {
            out.put(R.s(r, "seq_pi_no"), R.l(r, "n"));
        }
        return out;
    }

    private Map<String, Map<String, Object>> running(Collection<String> pis) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (pis.isEmpty()) {
            return out;
        }
        for (Map<String, Object> j : mapper.runningJobs(pis)) {
            out.put(
                    R.s(j, "pi_no"),
                    R.of(
                            "gen_batch", R.s(j, "gen_batch"),
                            "qty", R.l(j, "qty"),
                            "done_qty", R.l(j, "done_qty")));
        }
        return out;
    }

    private record IssueSummary(boolean needRule, boolean cannot, Map<String, Long> reasons) {}

    private IssueSummary lineIssueSummary(String pi, List<String> scope) {
        List<String[]> keys = new ArrayList<>();
        for (Map<String, Object> r : mapper.linesOfPi(pi, scope)) {
            keys.add(new String[] {R.s(r, "bill_no"), Integer.toString(R.i(r, "seq"))});
        }
        List<GenerateServiceImpl.GenLine> ls = generate.loadLines(pi, keys);
        List<GenerateServiceImpl.GenLine> todo = ls.stream().filter(x -> x.quota() > 0).toList();
        Map<String, Long> reasons = new LinkedHashMap<>();
        for (GenerateServiceImpl.GenLine x : todo) {
            for (String i : x.issues) {
                reasons.merge(i, 1L, Long::sum);
            }
        }
        boolean needRule =
                todo.stream()
                        .anyMatch(
                                x ->
                                        x.issues.contains("NO_RULE")
                                                || x.issues.contains("RULE_PREFIX"));
        boolean cannot = !todo.isEmpty() && todo.stream().allMatch(x -> !x.issues.isEmpty());
        return new IssueSummary(needRule, cannot, reasons);
    }

    /** 生成页 PI 列表与上方汇总；tab：TO_GENERATE（含生成中）/ GENERATED。 */
    public Map<String, Object> pis(CurrentUser cu, String q, String tab, boolean byModify) {
        cu.requireOperator();
        List<String> scope = cu.readFactories(null);
        String text = Util.trim(q);
        List<Map<String, Object>> rows =
                mapper.piRows(
                        text.isEmpty() ? null : Util.likeAny(text), byModify, scope, LIST_LIMIT);
        boolean capped = rows.size() >= LIST_LIMIT;
        List<String> names = rows.stream().map(r -> R.s(r, "pi")).toList();
        Map<String, Map<String, Long>> stats = itemStats(names, scope, true);
        Map<String, Long> own = genOwn(names, scope);
        Map<String, Map<String, Object>> running = running(names);
        Map<String, Long> frozen = new HashMap<>();
        for (Map<String, Object> r : mapper.frozen(scope)) {
            frozen.put(R.s(r, "pi"), R.l(r, "n"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            String pi = R.s(r, "pi");
            Map<String, Long> s = stats.getOrDefault(pi, Map.of());
            long quota = R.l(r, "quota");
            long pending = s.getOrDefault("pending", 0L);
            boolean interrupted =
                    s.getOrDefault("interrupted", 0L) > 0
                            || (s.getOrDefault("unbatched", 0L) > 0
                                    && s.getOrDefault("tasks", 0L) == 0);
            String status = null;
            Map<String, Long> reasons = new LinkedHashMap<>();
            if (running.containsKey(pi)) {
                status = GENERATING;
            } else if (quota > 0 && i < DETAIL_ROWS) {
                IssueSummary is = lineIssueSummary(pi, scope);
                reasons = is.reasons();
                status =
                        is.cannot() && !is.needRule() ? CANNOT : (is.needRule() ? NEED_RULE : null);
            }
            if (status == null) {
                if (interrupted) {
                    status = PUSH_INTERRUPTED;
                } else if (quota > 0) {
                    status = R.l(r, "gen_sum") > 0 ? PARTIAL : TO_GENERATE;
                } else if (pending > 0) {
                    status = TO_ALLOCATE;
                } else {
                    status = DONE;
                }
            }
            List<String> orgs = new ArrayList<>();
            for (String o : R.sz(r, "orgs").split(",")) {
                if (!o.isEmpty()) {
                    orgs.add(o);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pi_no", pi);
            m.put("customer_code", R.s(r, "customer_code"));
            m.put("bills", R.l(r, "bills"));
            m.put("modify_time", OrderServiceImpl.k3Time(R.t(r, "modify_time")));
            m.put("orgs", orgs);
            m.put("total_qty", R.l(r, "total_qty"));
            m.put("generated_qty", own.getOrDefault(pi, 0L));
            m.put("transferred_in", s.getOrDefault("transferred_in", 0L));
            m.put("allocated_qty", s.getOrDefault("allocated", 0L));
            m.put("pending_alloc", pending);
            m.put("quota", quota);
            m.put("unbatched", s.getOrDefault("unbatched", 0L));
            m.put("suspended", s.getOrDefault("suspended", 0L));
            m.put("frozen_bills", frozen.getOrDefault(pi, 0L));
            m.put("push_interrupted", interrupted);
            m.put("running", running.get(pi));
            m.put("status", status);
            m.put("reasons", reasons);
            out.add(m);
        }
        String t = Util.trim(tab);
        List<Map<String, Object>> shown = new ArrayList<>();
        for (Map<String, Object> x : out) {
            boolean gen = GENERATING.equals(x.get("status"));
            long quota = R.l(x, "quota");
            if ("TO_GENERATE".equals(t)) {
                if (quota > 0 || gen) {
                    shown.add(x);
                }
            } else if ("GENERATED".equals(t)) {
                if (quota == 0 && !gen) {
                    shown.add(x);
                }
            } else {
                shown.add(x);
            }
        }
        if ("TO_GENERATE".equals(t)) {
            shown.sort(Comparator.comparing(x -> !GENERATING.equals(x.get("status"))));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("quota", out.stream().mapToLong(x -> R.l(x, "quota")).sum());
        summary.put("pending_alloc", out.stream().mapToLong(x -> R.l(x, "pending_alloc")).sum());
        summary.put("unbatched", out.stream().mapToLong(x -> R.l(x, "unbatched")).sum());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", shown);
        res.put("capped", capped);
        res.put("summary", summary);
        res.put("synced_at", mapper.maxSynced());
        return Camel.map(res);
    }

    /** 生成详情：PI 头、各单据及物料行、规则选项、计数、生成任务、空号段。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> context(CurrentUser cu, String piIn, String billNo) {
        cu.requireOperator();
        String pi = Util.trim(piIn);
        if (pi.isEmpty() && !Util.blank(billNo)) {
            List<Map<String, Object>> ls = orders.lines(billNo);
            if (ls.isEmpty()) {
                Map<String, Object> led = changes.ledger(Util.trim(billNo));
                pi = led == null ? "" : R.sz(led, "pi");
            } else {
                pi = R.s(ls.getFirst(), "pi");
            }
        }
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        List<String> scope = cu.readFactories(null);
        List<Map<String, Object>> snap = mapper.linesOfPi(pi, scope);
        OrderServiceImpl.Extras ex = orders.lineExtras(snap);
        String customer = null;
        for (Map<String, Object> r : snap) {
            if (!Util.blank(R.s(r, "customer_code"))) {
                customer = R.s(r, "customer_code");
                break;
            }
        }
        if (customer == null) {
            customer = orders.customerOfPi(pi);
        }
        if (customer == null) {
            customer = "";
        }
        long last = generate.lastSeq(pi);
        List<String[]> keys = new ArrayList<>();
        for (Map<String, Object> r : snap) {
            keys.add(new String[] {R.s(r, "bill_no"), Integer.toString(R.i(r, "seq"))});
        }
        Map<String, GenerateServiceImpl.GenLine> ls = new HashMap<>();
        for (GenerateServiceImpl.GenLine x : generate.loadLines(pi, keys)) {
            ls.put(R.key(x.billNo, x.seq), x);
        }
        Map<String, Map<String, Object>> bills = new LinkedHashMap<>();
        Map<String, LocalDateTime> mts = new HashMap<>();
        for (Map<String, Object> r : snap) {
            String k = R.key(r);
            String no = R.s(r, "bill_no");
            Map<String, Object> b =
                    bills.computeIfAbsent(
                            no,
                            x -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("bill_no", no);
                                m.put("po", R.s(r, "po"));
                                m.put("customer_code", R.s(r, "customer_code"));
                                m.put("org_code", R.s(r, "org_code"));
                                m.put("org_name", R.s(r, "org_name"));
                                m.put("in_snapshot", true);
                                m.put("lines", new ArrayList<GenLineVO>());
                                return m;
                            });
            LocalDateTime mt = R.t(r, "modify_time");
            if (mt != null && (mts.get(no) == null || mt.isAfter(mts.get(no)))) {
                mts.put(no, mt);
            }
            GenerateServiceImpl.GenLine x = ls.get(k);
            OrderLineVO base =
                    orders.lineView(
                            r,
                            ex.counts().get(k),
                            ex.attaches().get(k),
                            rules.info(x.res, x.length, last + 1, x.unit),
                            ex.lengths().get(k),
                            ex.units().get(k));
            GenLineVO v = copy(base);
            v.issues = x.issues;
            ((List<GenLineVO>) b.get("lines")).add(v);
        }
        Map<String, long[]> items = new HashMap<>();
        Map<String, String[]> lineSn = new HashMap<>();
        for (Map<String, Object> r : mapper.itemLines(pi, scope)) {
            long[] pq = items.getOrDefault(R.s(r, "bill_no"), new long[2]);
            items.put(
                    R.s(r, "bill_no"),
                    new long[] {pq[0] + R.l(r, "pending"), pq[1] + R.l(r, "to_push")});
            lineSn.put(
                    R.key(R.s(r, "bill_no"), R.i(r, "bill_seq")),
                    new String[] {R.s(r, "a"), R.s(r, "b")});
        }
        for (Map<String, Object> b : bills.values()) {
            for (GenLineVO v : (List<GenLineVO>) b.get("lines")) {
                String[] ab = lineSn.getOrDefault(R.key(v.billNo, v.seq), new String[2]);
                v.startSn = ab[0];
                v.endSn = ab[1];
            }
        }
        Set<String> inPi = new HashSet<>();
        for (Map<String, Object> r : snap) {
            inPi.add(R.s(r, "bill_no"));
        }
        Set<String> snapAll = new HashSet<>();
        for (Map<String, Object> r : orders.linesOfPi(pi)) {
            snapAll.add(R.s(r, "bill_no"));
        }
        for (String no : new TreeSet<>(items.keySet())) {
            if (snapAll.contains(no)) {
                continue;
            }
            Map<String, Object> led = changes.ledger(no);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bill_no", no);
            m.put("po", "");
            m.put("customer_code", led == null ? "" : R.sz(led, "customer_code"));
            m.put("org_code", led == null ? "" : R.sz(led, "org_code"));
            m.put("org_name", led == null ? "" : R.sz(led, "org_name"));
            m.put("in_snapshot", !orders.lines(no).isEmpty());
            m.put("lines", new ArrayList<GenLineVO>());
            bills.put(no, m);
        }
        if (scope != null && bills.isEmpty()) {
            throw Biz.err(SnErrorCode.FACTORY_FORBIDDEN, "factory", cu.scopeText());
        }
        Map<String, BillStateVO> states = changes.billStates(bills.keySet());
        Map<String, Object> gap = generate.gapsOf(pi);
        Map<String, long[]> segCounts =
                generate.billSegmentCounts(pi, new ArrayList<>(bills.keySet()), scope);
        Map<String, long[]> freeCounts = new HashMap<>();
        List<Map<String, Object>> freeSegs =
                Boolean.TRUE.equals(gap.get("truncated"))
                        ? generate.freeSegments(pi, R.l(gap, "last_seq"))
                        : (List<Map<String, Object>>) gap.get("segments");
        for (Map<String, Object> g : freeSegs) {
            long[] c = freeCounts.computeIfAbsent(R.s(g, "bill_no"), k -> new long[2]);
            c[0] += 1;
            c[1] += R.l(g, "qty");
        }
        for (Map.Entry<String, Map<String, Object>> en : bills.entrySet()) {
            String no = en.getKey();
            Map<String, Object> b = en.getValue();
            long[] uc = segCounts.getOrDefault(no, new long[2]);
            long[] fc = freeCounts.getOrDefault(no, new long[2]);
            b.put(
                    "segments",
                    R.of(
                            "use_count",
                            uc[0],
                            "use_qty",
                            uc[1],
                            "free_count",
                            fc[0],
                            "free_qty",
                            fc[1]));
            b.put("modify_time", OrderServiceImpl.k3Time(mts.get(no)));
            b.put("state", states.getOrDefault(no, BillStateVO.empty()));
            long[] pq = items.get(no);
            b.put("pending_alloc", pq == null ? 0L : pq[0]);
            b.put("alloc_state", pq == null ? null : billState(pq[0], pq[1]));
            List<GenLineVO> lines = (List<GenLineVO>) b.get("lines");
            b.put("total_qty", lines.stream().mapToLong(x -> x.qty).sum());
            b.put("generated_qty", lines.stream().mapToLong(x -> x.generatedQty).sum());
            b.put("quota", lines.stream().mapToLong(x -> x.quota).sum());
        }
        Map<String, Long> stats = itemStats(List.of(pi), scope, true).getOrDefault(pi, Map.of());
        Map<String, Object> running = mapper.runningOfPi(pi);
        if (running != null) {
            List<Map<String, Object>> its =
                    GenerateServiceImpl.items(GenerateServiceImpl.loads(R.s(running, "plan_json")));
            if (its.stream().noneMatch(x -> GenerateServiceImpl.inScope(scope, x))) {
                running = null;
            }
        }
        List<Map<String, Object>> billList = new ArrayList<>(bills.values());
        billList.sort(
                Comparator.comparing((Map<String, Object> x) -> mts.get(R.s(x, "bill_no")) != null)
                        .thenComparing(x -> mts.getOrDefault(R.s(x, "bill_no"), LocalDateTime.MIN))
                        .thenComparing(x -> R.s(x, "bill_no"))
                        .reversed());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_qty", billList.stream().mapToLong(b -> R.l(b, "total_qty")).sum());
        summary.put("generated_qty", genOwn(List.of(pi), scope).getOrDefault(pi, 0L));
        summary.put("transferred_in", stats.getOrDefault("transferred_in", 0L));
        summary.put("allocated_qty", stats.getOrDefault("allocated", 0L));
        summary.put("pending_alloc", stats.getOrDefault("pending", 0L));
        summary.put("quota", billList.stream().mapToLong(b -> R.l(b, "quota")).sum());
        summary.put("suspended", stats.getOrDefault("suspended", 0L));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pi_no", pi);
        out.put("customer_code", customer);
        out.put("bills", billList);
        out.put("counter", R.of("last_seq_dec", last, "next_seq", last + 1));
        out.put("summary", summary);
        out.put("rule_options", rules.optionsFor(pi, customer, scope));
        out.put("rule_prefix", rules.piPrefix(pi));
        out.put("limits", generate.limits());
        out.put(
                "running_job",
                running == null ? null : generate.progress(null, R.s(running, "gen_batch")));
        out.put("jobs", generate.jobs(pi, 20, scope));
        out.put("gaps", gap);
        return Camel.map(out);
    }

    static GenLineVO copy(OrderLineVO s) {
        GenLineVO v = new GenLineVO();
        for (java.lang.reflect.Field f : OrderLineVO.class.getFields()) {
            try {
                f.set(v, f.get(s));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return v;
    }
}
