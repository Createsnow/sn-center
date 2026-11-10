package com.adtek.sn.service.impl;

import com.adtek.framework.lock.NamedLockService;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.dto.LineRefDTO;
import com.adtek.sn.mapper.DeletionMapper;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnLocks;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 删号（原系统 app/services/deletion.py）：在一张 PI 下按单据、物料行，或单枚 / 一段 SN 删除号（状态不限），号从号池移除并存档；PI
 * 最大流水号不退回（留下空号段）；额度还给号现在挂的物料行；号所在的未确认或失败交付批次作废（其余的号回号池或当场重建批次）。与生成、分配、
 * 转移共用 PI 操作锁；只删本系统生成的号（导入的号不在范围内）。
 */
@Service
public class DeletionServiceImpl {
    public static final String DEFAULT_REASON = "生成页删除 SN";
    static final int CHUNK = 2000;

    private final DeletionMapper mapper;
    private final OrderServiceImpl orders;
    private final GenerateServiceImpl generate;
    private final ChangeServiceImpl changes;
    private final BillStatsServiceImpl billStats;
    private final DeliveryServiceImpl delivery;
    private final AuditServiceImpl audit;
    private final SnLocks locks;
    private final Tx tx;

    public DeletionServiceImpl(
            DeletionMapper mapper,
            OrderServiceImpl orders,
            GenerateServiceImpl generate,
            ChangeServiceImpl changes,
            BillStatsServiceImpl billStats,
            @Lazy DeliveryServiceImpl delivery,
            AuditServiceImpl audit,
            SnLocks locks,
            Tx tx) {
        this.mapper = mapper;
        this.orders = orders;
        this.generate = generate;
        this.changes = changes;
        this.billStats = billStats;
        this.delivery = delivery;
        this.audit = audit;
        this.locks = locks;
        this.tx = tx;
    }

    private record Target(Map<String, Object> tg, String desc) {}

    private Target target(
            String pi, List<String> bills, List<LineRefDTO> lines, String startSn, String endSn) {
        String a = Util.trim(startSn);
        Map<String, Object> tg = new HashMap<>();
        tg.put("pi", pi);
        if (!a.isEmpty()) {
            Map<String, Object> s = mapper.genItem(a, pi);
            if (s == null) {
                throw Biz.err(SnErrorCode.DELETE_SN_NOT_FOUND, "pi", pi, "sn", a);
            }
            String b = Util.trim(endSn);
            if (b.isEmpty()) {
                b = a;
            }
            Map<String, Object> e = b.equals(a) ? s : mapper.genItem(b, pi);
            if (e == null) {
                throw Biz.err(SnErrorCode.DELETE_SN_NOT_FOUND, "pi", pi, "sn", b);
            }
            if (!Objects.equals(R.s(e, "seq_pi_no"), R.s(s, "seq_pi_no"))
                    || !Objects.equals(R.s(e, "bill_no"), R.s(s, "bill_no"))
                    || R.i(e, "bill_seq") != R.i(s, "bill_seq")
                    || R.l(e, "seq_dec") < R.l(s, "seq_dec")) {
                throw Biz.err(SnErrorCode.DELETE_RANGE_INVALID);
            }
            tg.put("range", true);
            tg.put("bill", R.s(s, "bill_no"));
            tg.put("billSeq", R.i(s, "bill_seq"));
            tg.put("seqPi", R.s(s, "seq_pi_no"));
            tg.put("s", R.l(s, "seq_dec"));
            tg.put("e", R.l(e, "seq_dec"));
            return new Target(
                    tg, R.s(s, "bill_no") + "/" + R.i(s, "bill_seq") + " " + a + "~" + b);
        }
        LinkedHashSet<String> nos = new LinkedHashSet<>();
        if (bills != null) {
            for (String x : bills) {
                if (!Util.trim(x).isEmpty()) {
                    nos.add(Util.trim(x));
                }
            }
        }
        LinkedHashMap<String, Map<String, Object>> keys = new LinkedHashMap<>();
        if (lines != null) {
            for (LineRefDTO x : lines) {
                String b = Util.trim(x.billNo);
                if (!b.isEmpty()) {
                    int sq = x.seq == null ? 0 : x.seq;
                    keys.putIfAbsent(R.key(b, sq), R.of("billNo", b, "seq", sq));
                }
            }
        }
        if (nos.isEmpty() && keys.isEmpty()) {
            throw Biz.err(SnErrorCode.DELETE_TARGET_INVALID);
        }
        tg.put("range", false);
        tg.put("nos", new ArrayList<>(nos));
        tg.put("keys", new ArrayList<>(keys.values()));
        List<String> desc = new ArrayList<>(nos);
        for (Map<String, Object> k : keys.values()) {
            desc.add(R.s(k, "billNo") + "/" + R.i(k, "seq"));
        }
        return new Target(tg, String.join(" ", desc));
    }

    private Map<String, Object> summary(Map<String, Object> tg) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> r : mapper.byStatus(tg)) {
            byStatus.put(R.s(r, "status"), R.l(r, "n"));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> r : mapper.lines(tg)) {
            lines.add(
                    R.of(
                            "bill_no", R.s(r, "bill_no"),
                            "seq", R.i(r, "bill_seq"),
                            "material_code", R.s(r, "material_code"),
                            "qty", R.l(r, "n"),
                            "start_sn", R.s(r, "a"),
                            "end_sn", R.s(r, "b")));
        }
        Set<String> orgs = new TreeSet<>();
        for (String f : mapper.factories(tg)) {
            if (f != null && !f.isEmpty()) {
                orgs.add(f);
            }
        }
        orgs.addAll(orders.billOrgs(mapper.pendingBills(tg)));
        orgs.remove("-");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qty", byStatus.values().stream().mapToLong(Long::longValue).sum());
        m.put("by_status", byStatus);
        m.put("orgs", new ArrayList<>(orgs));
        m.put("lines", lines);
        m.put("batches", mapper.batches(tg));
        m.put("seq_pis", new ArrayList<>(new TreeSet<>(mapper.seqPis(tg))));
        m.put("transferred_in", mapper.transferredIn(tg));
        return m;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> preview(
            CurrentUser cu,
            String piIn,
            List<String> bills,
            List<LineRefDTO> lines,
            String startSn,
            String endSn) {
        cu.requireOperator();
        String pi = Util.trim(piIn);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        Target t = target(pi, bills, lines, startSn, endSn);
        Map<String, Object> s = summary(t.tg());
        List<String> orgs = (List<String>) s.get("orgs");
        if (!orgs.isEmpty()) {
            cu.checkWrite(orgs.toArray(String[]::new));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pi_no", pi);
        out.put("scope_text", t.desc());
        out.putAll(s);
        out.put("gaps", generate.gapsOf(pi));
        return out;
    }

    /** 作废的批次与重建（事务外收尾用）。 */
    private record Voided(Map<String, Object> batch, Set<String> bills) {}

    @SuppressWarnings("unchecked")
    public Map<String, Object> delete(
            CurrentUser cu,
            String piIn,
            List<String> bills,
            List<LineRefDTO> lines,
            String startSn,
            String endSn,
            String reasonIn) {
        cu.requireOperator();
        String pi = Util.trim(piIn);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        String r0 = Util.trim(reasonIn);
        String reason = r0.isEmpty() ? DEFAULT_REASON : r0;
        if (reason.length() > 500) {
            throw Biz.err(SnErrorCode.REASON_REQUIRED);
        }
        Target t = target(pi, bills, lines, startSn, endSn);
        Map<String, Object> tg = t.tg();
        Set<String> seqPis = new TreeSet<>(mapper.seqPis(tg));
        List<String> lockKeys = new ArrayList<>(seqPis);
        lockKeys.add(pi);
        List<Voided> voided = new ArrayList<>();
        List<String> rebuilt = new ArrayList<>();
        NamedLockService.Held h = locks.piLock(lockKeys.toArray(String[]::new));
        Map<String, Object> result;
        try {
            result =
                    tx.run(
                            () -> {
                                Set<String> held = new TreeSet<>(seqPis);
                                held.add(pi);
                                if (mapper.anyRunning(held)) {
                                    throw Biz.err(SnErrorCode.GEN_BUSY, "pi", String.join("、", held));
                                }
                                Map<String, Object> s = summary(tg);
                                if (R.l(s, "qty") == 0) {
                                    throw Biz.err(SnErrorCode.DELETE_NOTHING);
                                }
                                if (!held.containsAll((List<String>) s.get("seq_pis"))) {
                                    throw Biz.err(SnErrorCode.CONFLICT_RETRY);
                                }
                                List<String> orgs = (List<String>) s.get("orgs");
                                if (!orgs.isEmpty()) {
                                    cu.checkWrite(orgs.toArray(String[]::new));
                                }
                                List<Map<String, Object>> batches = new ArrayList<>();
                                for (String no : (List<String>) s.get("batches")) {
                                    Map<String, Object> b = delivery.mustBatch(no, true);
                                    if ("UNCONFIRMED".equals(R.s(b, "result"))
                                            || "FAILED".equals(R.s(b, "result"))) {
                                        batches.add(b);
                                    }
                                }
                                List<Long> ids = mapper.lockIds(tg);
                                LocalDateTime now = Util.now();
                                String delNo = Util.nextId("D");
                                List<Map<String, Object>> perLine = mapper.perLine(tg);
                                List<Map<String, Object>> perPi = mapper.perPi(tg);
                                billStats.touchIds(ids);
                                for (List<Long> part : R.chunks(ids, CHUNK)) {
                                    mapper.archive(part, delNo, cu.empNo(), now, reason);
                                    mapper.deleteItems(part);
                                }
                                for (Map<String, Object> r : perLine) {
                                    mapper.giveBack(
                                            R.s(r, "bill_no"),
                                            R.i(r, "bill_seq"),
                                            R.l(r, "n"),
                                            R.l(r, "a"),
                                            now);
                                }
                                for (Map<String, Object> r : perPi) {
                                    mapper.reduceGenerated(R.s(r, "seq_pi_no"), R.l(r, "n"), now);
                                }
                                Set<String> billsHit = new TreeSet<>();
                                for (Map<String, Object> r : perLine) {
                                    billsHit.add(R.s(r, "bill_no"));
                                }
                                changes.refreshItems(billsHit, false);
                                for (Map<String, Object> b : batches) {
                                    DeliveryServiceImpl.VoidResult vr =
                                            delivery.voidInTx(
                                                    cu,
                                                    b,
                                                    Util.cut("删除 SN（" + delNo + "）：" + reason, 500),
                                                    delNo);
                                    voided.add(new Voided(b, vr.bills()));
                                    for (Map<String, Object> nb : vr.newBatches()) {
                                        rebuilt.add(R.s(nb, "batch_no"));
                                    }
                                }
                                List<Map<String, Object>> sorted = new ArrayList<>(perLine);
                                sorted.sort(
                                        Comparator.comparing((Map<String, Object> x) -> R.s(x, "bill_no"))
                                                .thenComparingInt(x -> R.i(x, "bill_seq")));
                                for (Map<String, Object> r : sorted) {
                                    String st = R.sz(r, "st");
                                    audit.record(
                                            cu,
                                            AuditEntryDTO.of(AuditAction.SN_DELETE)
                                                    .factory(R.l(r, "nf") == 1 ? R.s(r, "f") : null)
                                                    .pi(pi)
                                                    .bill(R.s(r, "bill_no"))
                                                    .range(R.s(r, "first_sn"), R.s(r, "last_sn"))
                                                    .count(R.l(r, "n"))
                                                    .status(st.contains(",") ? null : st, null)
                                                    .batch(delNo)
                                                    .why(reason)
                                                    .info(
                                                            "seq="
                                                                    + R.i(r, "bill_seq")
                                                                    + " statuses="
                                                                    + st
                                                                    + " scope="
                                                                    + Util.cut(t.desc(), 200)));
                                }
                                List<Map<String, Object>> outLines = new ArrayList<>();
                                for (Map<String, Object> r : sorted) {
                                    outLines.add(
                                            R.of(
                                                    "bill_no", R.s(r, "bill_no"),
                                                    "seq", R.i(r, "bill_seq"),
                                                    "qty", R.l(r, "n")));
                                }
                                Map<String, Object> out = new LinkedHashMap<>();
                                out.put("del_no", delNo);
                                out.put("pi_no", pi);
                                out.put("qty", (long) ids.size());
                                out.put("lines", outLines);
                                return out;
                            });
        } finally {
            h.release();
        }
        for (Voided v : voided) {
            delivery.afterVoid(cu, v.batch(), v.bills());
        }
        result.put("voided", voided.stream().map(v -> R.s(v.batch(), "batch_no")).toList());
        result.put("rebuilt", rebuilt);
        result.put("gaps", generate.gapsOf(pi));
        return result;
    }

    public List<Map<String, Object>> deleted(CurrentUser cu, String piIn, int limit) {
        cu.requireOperator();
        String pi = Util.trim(piIn);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.deleted(pi, Math.min(Math.max(limit, 1), 500))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("del_no", R.s(r, "del_no"));
            m.put("start_sn", R.s(r, "start_sn"));
            m.put("end_sn", R.s(r, "end_sn"));
            m.put("qty", R.l(r, "qty"));
            m.put("bills", List.of(R.sz(r, "bills").split(",", -1)));
            m.put("deleted_by", R.s(r, "deleted_by"));
            m.put("deleted_at", R.t(r, "deleted_at"));
            m.put("reason", R.s(r, "reason"));
            out.add(m);
        }
        return out;
    }
}
