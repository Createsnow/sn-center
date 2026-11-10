package com.adtek.sn.service.impl;

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
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.PiBillVO;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 交付批次的建立（原系统 delivery/batches.py）：批次号、报文（各类批次明细同一结构）、写定请求体与明细；从「待推送、未入批、未暂停」号池认领 （SELECT … FOR
 * UPDATE SKIP LOCKED）。批次号、所含 SN 及其挂靠和 rev 建立后都不再改变。
 */
@Service
public class BatchServiceImpl {
    public static final String UNCONFIRMED = "UNCONFIRMED";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String VOIDED = "VOIDED";
    public static final String SRC_PUSH = "PUSH";
    public static final String SRC_PULL = "PULL";
    public static final String NEW = "NEW";
    public static final String SAME_ORG_TRANSFER = "SAME_ORG_TRANSFER";
    public static final String TRANSFER_IN = "TRANSFER_IN";
    public static final String REMAP = "REMAP";
    public static final String TRANSFER_OUT = "TRANSFER_OUT";
    public static final String M_RECEIPT = "RECEIPT";
    public static final String M_MANUAL = "MANUAL";
    public static final String XO_KEY = "#XO";
    public static final String RECEIPT_OK = "OK";
    public static final String RECEIPT_NG = "NG";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DeliveryMapper mapper;
    private final FactoryServiceImpl factories;
    private final RuleServiceImpl rules;
    private final BillStatsServiceImpl billStats;
    private final AuditServiceImpl audit;
    private final Tx tx;

    public BatchServiceImpl(
            DeliveryMapper mapper,
            FactoryServiceImpl factories,
            RuleServiceImpl rules,
            BillStatsServiceImpl billStats,
            AuditServiceImpl audit,
            Tx tx) {
        this.mapper = mapper;
        this.factories = factories;
        this.rules = rules;
        this.billStats = billStats;
        this.audit = audit;
        this.tx = tx;
    }

    public Map<String, Object> findBatch(String batchNo, boolean lock) {
        if (Util.blank(batchNo)) {
            return null;
        }
        String no = Util.trim(batchNo);
        return lock ? mapper.batchLock(no) : mapper.batch(no);
    }

    public Map<String, Object> mustBatch(String batchNo, boolean lock) {
        Map<String, Object> b = findBatch(batchNo, lock);
        if (b == null) {
            throw Biz.err(SnErrorCode.BATCH_NOT_FOUND, "batch_no", Util.trim(batchNo));
        }
        return b;
    }

    public int batchSize(String org) {
        FactoryEntity f = factories.get(org);
        return f == null ? 5000 : Math.max(1, f.batchSize);
    }

    /** PS- / PL- / XO- + 组织 + yyyymmdd（UTC+8）+ 当天序号（≤ 40 位，全局唯一）；序号在独立短事务里加 1。 */
    String batchNo(String prefix, String org) {
        String day = DAY.format(Util.mesLocal(null));
        String name = prefix + "-" + org + "-" + day;
        long n =
                DbErrors.retryDeadlock(
                        () ->
                                tx.isolated(
                                        () -> {
                                            if (!mapper.seqExists(name)) {
                                                mapper.seqInsert(name);
                                            }
                                            mapper.seqBump(name);
                                            return mapper.seqValue(name);
                                        }),
                        DbErrors.DEADLOCK_ATTEMPTS,
                        true);
        return prefix
                + "-"
                + (org.length() > 16 ? org.substring(0, 16) : org)
                + "-"
                + day
                + "-"
                + String.format("%06d", n);
    }

    static String digest(List<Map<String, Object>> items) {
        return "sha256:" + MesWire.sha256Hex(MesWire.canonical(items));
    }

    static String strOrNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    /** 号该进哪类交付批次（与 mapper typeSql 一致）。 */
    static String itemType(Map<String, Object> r, String org) {
        String from = R.s(r, "transfer_from_org");
        if (from != null) {
            return from.equals(org) ? SAME_ORG_TRANSFER : TRANSFER_IN;
        }
        return Util.PUSH_REASSIGN.equals(R.s(r, "push_type")) ? REMAP : NEW;
    }

    /** rows 里 SN 导入的号（seq_pi_no 为空串，seq_dec 即导入记录 id）的导入记录；没有导入的号时不查库。 */
    public Map<Long, Map<String, Object>> importDetails(List<Map<String, Object>> rows) {
        TreeSet<Long> ids = new TreeSet<>();
        for (Map<String, Object> r : rows) {
            if (R.sz(r, "seq_pi_no").isEmpty()) {
                ids.add(R.l(r, "seq_dec"));
            }
        }
        Map<Long, Map<String, Object>> out = new HashMap<>();
        for (List<Long> part : R.chunks(ids, 2000)) {
            for (Map<String, Object> d : mapper.importDetails(part)) {
                out.put(R.l(d, "id"), d);
            }
        }
        return out;
    }

    /** SN 导入的号的明细（结构、字段顺序与生成的号相同）；本系统生成的号返回 null。 */
    public static Map<String, Object> importedItem(
            Map<String, Object> r, Map<Long, Map<String, Object>> details) {
        if (!R.sz(r, "seq_pi_no").isEmpty()) {
            return null;
        }
        Map<String, Object> d = details.get(R.l(r, "seq_dec"));
        Long seq =
                R.l(r, "bill_seq") != 0
                        ? Long.valueOf(R.l(r, "bill_seq"))
                        : (d == null ? null : R.lo(d, "cur_bill_seq"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sn", R.s(r, "sn"));
        m.put("rev", R.l(r, "rev"));
        m.put("serial", d == null ? null : R.s(d, "serial_text"));
        m.put("serialDec", d == null ? null : String.valueOf(R.l(d, "seq_dec")));
        m.put("serialPi", d == null ? null : R.s(d, "pi_no"));
        m.put("pi", R.s(r, "pi_no"));
        m.put("billNo", R.s(r, "bill_no"));
        m.put("billSeq", seq);
        m.put("attachNo", strOrNull(R.s(r, "attach_no")));
        m.put("materialCode", strOrNull(R.s(r, "material_code")));
        m.put("length", strOrNull(R.s(r, "length_text")));
        m.put("customerCode", strOrNull(R.s(r, "customer_code")));
        return m;
    }

    /** 批次明细，交付批次与转出批次同一结构。 */
    public Map<String, Object> itemPayload(
            Map<String, Object> r, Map<Long, Map<String, Object>> details) {
        Map<String, Object> imp = importedItem(r, details);
        if (imp != null) {
            return imp;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sn", R.s(r, "sn"));
        m.put("rev", R.l(r, "rev"));
        m.put("serial", rules.seqText(R.lo(r, "rule_version_id"), R.lo(r, "seq_dec")));
        m.put("serialDec", String.valueOf(R.l(r, "seq_dec")));
        m.put("serialPi", R.s(r, "seq_pi_no"));
        m.put("pi", R.s(r, "pi_no"));
        m.put("billNo", R.s(r, "bill_no"));
        m.put("billSeq", R.l(r, "bill_seq"));
        m.put("attachNo", strOrNull(R.s(r, "attach_no")));
        m.put("materialCode", strOrNull(R.s(r, "material_code")));
        m.put("length", R.s(r, "length_text"));
        m.put("customerCode", R.s(r, "customer_code"));
        return m;
    }

    private Map<String, Object> insertBatch(
            CurrentUser cu,
            String prefix,
            String source,
            String batchType,
            String org,
            String pi,
            List<Map<String, Object>> payload,
            List<Map<String, Object>> rows,
            String transferKind,
            String transferDocNo,
            String rebuiltFrom,
            String toOrg) {
        String no = batchNo(prefix, org);
        LocalDateTime now = Util.now();
        String digest = digest(payload);
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("schemaVersion", "1.0");
        obj.put("batchNo", no);
        obj.put("batchType", batchType);
        obj.put("orgCode", org);
        obj.put("toOrgCode", toOrg);
        obj.put("pi", pi);
        obj.put("count", payload.size());
        obj.put("createdAt", Util.mesFmt(now));
        obj.put("itemsDigest", digest);
        obj.put("items", payload);
        byte[] body = MesWire.gzip(MesWire.canonical(obj));
        Map<String, Object> e = new HashMap<>();
        e.put("batchNo", no);
        e.put("source", source);
        e.put("batchType", batchType);
        e.put("factoryCode", org);
        e.put("piNo", pi);
        e.put("qty", payload.size());
        e.put("itemsDigest", digest);
        e.put("bodyGz", body);
        e.put("transferKind", transferKind);
        e.put("transferDocNo", transferDocNo);
        e.put("rebuiltFrom", rebuiltFrom);
        e.put("createdBy", cu.empNo());
        e.put("createdAt", now);
        mapper.insertBatch(e);
        long id = ((Number) e.get("id")).longValue();
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).put("line_no", i + 1);
        }
        for (List<Map<String, Object>> part : R.chunks(rows, 1000)) {
            mapper.insertItems(id, part);
        }
        return mapper.batchById(id);
    }

    private static Map<String, Object> itemRow(
            Map<String, Object> r, String pushType, String toFactory) {
        Map<String, Object> m = new HashMap<>();
        m.put("item_id", R.lo(r, r.containsKey("item_id") ? "item_id" : "id"));
        m.put("sn", R.s(r, "sn"));
        m.put("rev", R.lo(r, "rev"));
        m.put("push_type", pushType);
        m.put("pi_no", R.s(r, "pi_no"));
        m.put("bill_no", R.s(r, "bill_no"));
        m.put("bill_seq", R.lo(r, "bill_seq"));
        m.put("material_code", R.s(r, "material_code"));
        m.put("customer_code", R.s(r, "customer_code"));
        m.put("seq_pi_no", R.s(r, "seq_pi_no"));
        m.put("seq_dec", R.lo(r, "seq_dec"));
        m.put("to_factory", toFactory);
        return m;
    }

    /** 从号池按流水顺序认领（当前事务内加锁，已被别处锁住的跳过）。 */
    public List<Map<String, Object>> claim(
            String org, String pi, int limit, String bill, String btype, Boolean imported) {
        return mapper.claim(org, pi, limit, Util.trimOrNull(bill), btype, imported);
    }

    /** 号池按（类型，是否 SN 导入的号，枚数）分组：生成的号在前，各自成批。 */
    public record PoolGroup(String type, boolean imported, long n) {}

    public List<PoolGroup> poolGroups(String org, String pi, String bill) {
        List<PoolGroup> out = new ArrayList<>();
        for (Map<String, Object> r : mapper.poolGroups(org, pi, Util.trimOrNull(bill))) {
            out.add(new PoolGroup(R.s(r, "t"), R.b(r, "imp"), R.l(r, "n")));
        }
        return out;
    }

    private Map<String, Object> deliver(
            CurrentUser cu,
            String org,
            String pi,
            String source,
            String btype,
            List<Map<String, Object>> items,
            String bill,
            String transferKind,
            String transferDocNo,
            String rebuiltFrom) {
        Map<Long, Map<String, Object>> details = importDetails(items);
        List<Map<String, Object>> payload = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : items) {
            payload.add(itemPayload(r, details));
            rows.add(itemRow(r, R.s(r, "push_type"), null));
        }
        Map<String, Object> b =
                insertBatch(
                        cu,
                        SRC_PUSH.equals(source) ? "PS" : "PL",
                        source,
                        btype,
                        org,
                        pi,
                        payload,
                        rows,
                        transferKind,
                        transferDocNo,
                        rebuiltFrom,
                        null);
        List<Long> ids = items.stream().map(r -> R.l(r, "id")).toList();
        for (List<Long> part : R.chunks(ids, 1000)) {
            int n = mapper.assignBatch(R.s(b, "batch_no"), Util.now(), part);
            if (n != part.size()) {
                throw Biz.err(SnErrorCode.CONFLICT_RETRY);
            }
        }
        billStats.touchMany(
                items.stream().map(r -> new PiBillVO(R.s(r, "pi_no"), R.s(r, "bill_no"))).toList());
        audit.record(
                cu,
                AuditEntryDTO.of(AuditAction.SN_BATCH_CREATE)
                        .factory(org)
                        .pi(pi)
                        .bill(bill)
                        .count(items.size())
                        .range(R.s(items.get(0), "sn"), R.s(items.get(items.size() - 1), "sn"))
                        .batch(R.s(b, "batch_no"))
                        .transfer(transferDocNo)
                        .status(Util.TO_PUSH, Util.TO_PUSH)
                        .info(
                                "source="
                                        + source
                                        + " type="
                                        + btype
                                        + (rebuiltFrom != null
                                                ? " rebuilt_from=" + rebuiltFrom
                                                : "")));
        return b;
    }

    /** 从号池认领并建立一个交付批次（短事务）：取最早的号所属的类型，只认领该类型；没有可认领的号返回 null。 */
    public Map<String, Object> buildDelivery(
            CurrentUser cu, String org, String pi, String source, String bill) {
        FactoryEntity f = factories.mustGet(org);
        int size = Math.max(1, f.batchSize);
        return tx.run(
                () -> {
                    for (PoolGroup g : poolGroups(org, pi, bill)) {
                        List<Map<String, Object>> items =
                                claim(org, pi, size, bill, g.type(), g.imported());
                        if (!items.isEmpty()) {
                            return deliver(
                                    cu, org, pi, source, g.type(), items, bill, null, null, null);
                        }
                    }
                    return null;
                });
    }

    /** 当场建批（在转移、确认物料对应、告警重推、作废重建的事务内调用）：按类型分批，按组织当前单批枚数拆分。 */
    public List<Map<String, Object>> buildDeliveryNow(
            CurrentUser cu,
            String org,
            String pi,
            List<Long> ids,
            String transferKind,
            String transferDocNo,
            String rebuiltFrom) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (List<Long> part : R.chunks(ids, 1000)) {
            items.addAll(mapper.lockPoolIds(part, org, pi));
        }
        items.sort(
                Comparator.comparing((Map<String, Object> r) -> R.sz(r, "seq_pi_no"))
                        .thenComparingLong(r -> R.l(r, "seq_dec")));
        int size = batchSize(org);
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (Map<String, Object> r : items) {
            String k = itemType(r, org) + "|" + R.sz(r, "seq_pi_no").isEmpty();
            byType.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byType.entrySet()) {
            String btype = e.getKey().substring(0, e.getKey().indexOf('|'));
            for (List<Map<String, Object>> part : R.chunks(e.getValue(), size)) {
                out.add(
                        deliver(
                                cu,
                                org,
                                pi,
                                SRC_PUSH,
                                btype,
                                new ArrayList<>(part),
                                null,
                                transferKind,
                                transferDocNo,
                                rebuiltFrom));
            }
        }
        return out;
    }

    /**
     * 转出批次（在转移事务内调用）：按转出前 PI、转入组织分批，再按来源组织当前单批枚数拆分。entries 每枚含转出前挂靠（含米数、规则版本）、
     * 转入组织（to_factory）、转移类型与单号（transfer_kind、transfer_doc_no）。
     */
    public List<Map<String, Object>> buildTransferOut(
            CurrentUser cu, String org, List<Map<String, Object>> entries, String rebuiltFrom) {
        int size = batchSize(org);
        TreeMap<String, List<Map<String, Object>>> byKey = new TreeMap<>();
        for (Map<String, Object> e : entries) {
            byKey.computeIfAbsent(
                            R.s(e, "pi_no") + "\u0000" + R.s(e, "to_factory"),
                            x -> new ArrayList<>())
                    .add(e);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Map<String, Object>> group : byKey.values()) {
            for (List<Map<String, Object>> part : R.chunks(group, size)) {
                Map<String, Object> first = part.get(0);
                String pi = R.s(first, "pi_no");
                Map<Long, Map<String, Object>> details = importDetails(part);
                List<Map<String, Object>> payload = new ArrayList<>();
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Map<String, Object> e : part) {
                    payload.add(itemPayload(e, details));
                    rows.add(itemRow(e, Util.PUSH_TRANSFER_OUT, R.s(e, "to_factory")));
                }
                Map<String, Object> b =
                        insertBatch(
                                cu,
                                "XO",
                                SRC_PUSH,
                                TRANSFER_OUT,
                                org,
                                pi,
                                payload,
                                rows,
                                R.s(first, "transfer_kind"),
                                R.s(first, "transfer_doc_no"),
                                rebuiltFrom,
                                R.s(first, "to_factory"));
                audit.record(
                        cu,
                        AuditEntryDTO.of(AuditAction.SN_BATCH_CREATE)
                                .factory(org)
                                .pi(R.s(b, "pi_no"))
                                .count(part.size())
                                .range(R.s(first, "sn"), R.s(part.get(part.size() - 1), "sn"))
                                .batch(R.s(b, "batch_no"))
                                .transfer(R.s(first, "transfer_doc_no"))
                                .info(
                                        "source=PUSH type="
                                                + TRANSFER_OUT
                                                + (rebuiltFrom != null
                                                        ? " rebuilt_from=" + rebuiltFrom
                                                        : "")));
                out.add(b);
            }
        }
        return out;
    }

    /** 批次请求体（写定的报文）。 */
    public static Map<String, Object> bodyObj(Map<String, Object> b) {
        return MesWire.parse(MesWire.gunzip((byte[]) b.get("body_gz")));
    }
}
