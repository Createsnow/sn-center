package com.adtek.sn.service.impl;

import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.dto.TransferDTO;
import com.adtek.sn.dto.TransferLineDTO;
import com.adtek.sn.mapper.TransferMapper;
import com.adtek.sn.service.TransferService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.Camel;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnLocks;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.support.Xlsx;
import com.adtek.sn.vo.BatchVO;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.PiBillVO;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 转单（F-15）、转组织（F-16）与转移后的 MES 处理（5.2.8）（原系统 transfers.py）。都不改 SN 字符串，只在本系统改挂：每枚号清掉原批次、rev + 1； 转移前
 * MES 已持有、改挂后待推送的号同一事务内当场建批；号的 MES 持有组织为来源组织且转入组织不同时建「转出」批次。只有分配成功的号能转；页面统一入口按 {@link #decideKind}
 * 判断转单或转组织。
 */
@Service
public class TransferServiceImpl implements TransferService {
    public static final String KIND_BILL = "BILL";
    public static final String KIND_ORG = "ORG";
    public static final String KIND_AUTO = "AUTO";
    public static final String BILL_TRANSFER = "BILL_TRANSFER";
    public static final String ORG_TRANSFER = "ORG_TRANSFER";
    public static final String MODE_VOID = "VOID";
    public static final String MODE_SPLIT = "SPLIT";
    public static final String APPROVED = "APPROVED";
    public static final String SCOPE_BILL = "BILL";
    public static final String SCOPE_MATERIAL = "MATERIAL";
    public static final String SCOPE_RANGE = "RANGE";
    static final Set<String> SCOPES = Set.of(SCOPE_BILL, SCOPE_MATERIAL, SCOPE_RANGE);
    static final String PICK_ALL = "ALL";
    static final String PICK_RANGE = "RANGE";
    static final String PICK_QTY = "QTY";
    static final Set<String> PICK_MODES = Set.of(PICK_ALL, PICK_RANGE, PICK_QTY);
    static final List<String> ORG_FIELDS =
            List.of(
                    "id",
                    "transfer_no",
                    "mode",
                    "status",
                    "scope",
                    "from_factory",
                    "from_pi",
                    "from_bill",
                    "from_material",
                    "from_sn",
                    "from_end_sn",
                    "qty",
                    "to_factory",
                    "to_pi",
                    "to_customer",
                    "to_bill",
                    "to_seq",
                    "to_material",
                    "reason",
                    "applied_by",
                    "applied_at");
    static final List<String> BILL_FIELDS =
            List.of(
                    "id",
                    "transfer_no",
                    "mode",
                    "scope",
                    "from_bill",
                    "to_bill",
                    "from_pi",
                    "to_pi",
                    "to_factory",
                    "to_seq",
                    "to_material",
                    "scope_text",
                    "qty",
                    "reason",
                    "created_by",
                    "created_at");
    static final List<String> ALL_FIELDS =
            List.of(
                    "kind",
                    "id",
                    "transfer_no",
                    "mode",
                    "status",
                    "scope",
                    "scope_text",
                    "from_factory",
                    "from_pi",
                    "from_bill",
                    "from_material",
                    "from_sn",
                    "from_end_sn",
                    "to_factory",
                    "to_pi",
                    "to_bill",
                    "to_seq",
                    "to_material",
                    "to_customer",
                    "qty",
                    "reason",
                    "created_by",
                    "created_at");
    public static final List<String> OUT_LIST_HEADERS =
            List.of(
                    "序号",
                    "完整SN",
                    "进制流水",
                    "十进制流水",
                    "流水归属PI",
                    "组织编号",
                    "生产组织名称",
                    "转出前PI",
                    "转出前单据",
                    "附件项次",
                    "转出前物料",
                    "转出前ERP行号",
                    "米数",
                    "原交付批次",
                    "转出批次",
                    "转入生产组织",
                    "转入生产组织名称");
    static final Map<String, String> REASON_TEXT =
            Map.of(
                    "IMPORTED", "SN 导入的号没有导入记录（不知道在哪个物料行），不能转移",
                    "PENDING_ALLOC", "待分配（只有分配成功的号才能转移）",
                    "BATCH_PENDING", "所在批次未确认或失败",
                    "TRANSFER_OUT_PENDING", "所在转出批次未确认或失败，先在原转组织单上继续推送转出");

    private final TransferMapper mapper;
    private final DeliveryServiceImpl delivery;
    private final FactoryServiceImpl factories;
    private final OrderServiceImpl orders;
    private final ChangeServiceImpl changes;
    private final RuleServiceImpl rules;
    private final SnImportServiceImpl imports;
    private final BillStatsServiceImpl billStats;
    private final AuditServiceImpl audit;
    private final SnLocks locks;
    private final SnSettings settings;
    private final Tx tx;

    public TransferServiceImpl(
            TransferMapper mapper,
            @Lazy DeliveryServiceImpl delivery,
            FactoryServiceImpl factories,
            OrderServiceImpl orders,
            @Lazy ChangeServiceImpl changes,
            RuleServiceImpl rules,
            SnImportServiceImpl imports,
            BillStatsServiceImpl billStats,
            AuditServiceImpl audit,
            SnLocks locks,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.delivery = delivery;
        this.factories = factories;
        this.orders = orders;
        this.changes = changes;
        this.rules = rules;
        this.imports = imports;
        this.billStats = billStats;
        this.audit = audit;
        this.locks = locks;
        this.settings = settings;
        this.tx = tx;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo()).requireOperator();
    }

    private static String reason(String raw) {
        String r = Util.trim(raw);
        if (r.isEmpty() || r.length() > 500) {
            throw Biz.err(SnErrorCode.REASON_REQUIRED);
        }
        return r;
    }

    private static Map<String, Object> jrow(Map<String, Object> t, List<String> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : fields) {
            out.put(Camel.key(f), t.get(f));
        }
        return out;
    }

    // ---------------------------------------------------------------- 可转判断
    private static boolean isImport(Map<String, Object> r) {
        return Util.ORIGIN_IMPORT.equals(R.s(r, "origin"));
    }

    private static String attachOf(Map<String, Object> r) {
        return R.sz(r, "attach_no");
    }

    /** 号所在的物料行：生成的号为号池的单据序号；导入的号为导入记录的当前行号。 */
    private static long lineOf(Map<String, Object> r) {
        return isImport(r) ? R.l(r, "import_seq") : R.l(r, "bill_seq");
    }

    private List<Map<String, Object>> withImportLines(List<Map<String, Object>> items) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> r : items) {
            if (isImport(r)) {
                ids.add(R.l(r, "seq_dec"));
            }
        }
        if (!ids.isEmpty()) {
            Map<Long, Map<String, Object>> d = imports.details(ids);
            for (Map<String, Object> r : items) {
                if (isImport(r)) {
                    Map<String, Object> x = d.get(R.l(r, "seq_dec"));
                    r.put("import_seq", x == null ? null : R.l(x, "cur_bill_seq"));
                }
            }
        }
        return items;
    }

    /** 不能转的号及原因：IMPORTED / PENDING_ALLOC / BATCH_PENDING / TRANSFER_OUT_PENDING。 */
    Map<Long, String> blocked(List<Map<String, Object>> items) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map<String, Object> r : items) {
            long id = R.l(r, "id");
            if (isImport(r) && r.get("import_seq") == null) {
                out.put(id, "IMPORTED");
            } else if (Util.PENDING_ALLOC.equals(R.s(r, "status"))) {
                out.put(id, "PENDING_ALLOC");
            } else if (Util.TO_PUSH.equals(R.s(r, "status"))
                    && r.get("delivery_batch_no") != null) {
                out.put(id, "BATCH_PENDING");
            }
        }
        List<Long> rest = new ArrayList<>();
        for (Map<String, Object> r : items) {
            if (!out.containsKey(R.l(r, "id"))) {
                rest.add(R.l(r, "id"));
            }
        }
        for (Long i : delivery.frozenByTransferOut(rest)) {
            out.put(i, "TRANSFER_OUT_PENDING");
        }
        return out;
    }

    private static Map<String, Object> summary(
            List<Map<String, Object>> items, Map<Long, String> bad) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> byReason = new LinkedHashMap<>();
        for (Map<String, Object> r : items) {
            byStatus.merge(R.s(r, "status"), 1L, Long::sum);
            String b = bad.get(R.l(r, "id"));
            if (b != null) {
                byReason.merge(b, 1L, Long::sum);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", items.size());
        m.put("transferable", items.size() - bad.size());
        m.put("byStatus", byStatus);
        m.put("blocked", byReason);
        m.put("delivered", items.stream().filter(r -> r.get("mes_holder") != null).count());
        return m;
    }

    private static void checkAny(
            List<Map<String, Object>> items,
            List<Map<String, Object>> ok,
            Map<Long, String> bad,
            Source src) {
        if (!ok.isEmpty()) {
            return;
        }
        if (src.singleRange() != null && items.size() == 1) {
            throw Biz.err(
                    SnErrorCode.TR_SN_NOT_TRANSFERABLE,
                    "sn",
                    R.s(items.get(0), "sn"),
                    "reason",
                    REASON_TEXT.getOrDefault(bad.get(R.l(items.get(0), "id")), ""));
        }
        raiseIfBlocked(bad);
        throw Biz.err(SnErrorCode.TR_NOTHING);
    }

    private static void raiseIfBlocked(Map<Long, String> bad) {
        if (bad.isEmpty()) {
            return;
        }
        Map<String, Long> by = new LinkedHashMap<>();
        for (String v : bad.values()) {
            by.merge(v, 1L, Long::sum);
        }
        String detail =
                by.entrySet().stream()
                        .map(
                                e ->
                                        REASON_TEXT.getOrDefault(e.getKey(), e.getKey())
                                                + " "
                                                + e.getValue()
                                                + " 枚")
                        .collect(Collectors.joining("；"));
        throw Biz.err(SnErrorCode.TR_FROZEN, "count", bad.size(), "detail", detail);
    }

    // ---------------------------------------------------------------- 改挂（5.2.8）
    /** 一枚号转移后的挂靠。 */
    record Target(
            String factory, String pi, String customer, String bill, long seq, String material) {}

    /** 改挂后各列相同的号一组更新（字段供 Mapper 读取）。 */
    public static final class MoveGroup {
        public final String org;
        public final String pi;
        public final String customer;
        public final String bill;
        public final long seq;
        public final String material;
        public final String status;
        public final String pushType;
        public final String mode;
        public final String fromOrg;
        public final boolean keepFrom;

        MoveGroup(
                String org,
                String pi,
                String customer,
                String bill,
                long seq,
                String material,
                String status,
                String pushType,
                String mode,
                String fromOrg,
                boolean keepFrom) {
            this.org = org;
            this.pi = pi;
            this.customer = customer;
            this.bill = bill;
            this.seq = seq;
            this.material = material;
            this.status = status;
            this.pushType = pushType;
            this.mode = mode;
            this.fromOrg = fromOrg;
            this.keepFrom = keepFrom;
        }

        private List<Object> key() {
            return Arrays.asList(
                    org, pi, customer, bill, seq, material, status, pushType, mode, fromOrg,
                    keepFrom);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MoveGroup g && key().equals(g.key());
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }
    }

    /** sn_transfer_item 的一行（字段供 Mapper 读取）。 */
    public static final class TransferItemRow {
        public String kind;
        public long transferId;
        public long itemId;
        public String sn;
        public String seqPiNo;
        public Long seqDec;
        public String fromStatus;
        public String fromFactory;
        public String fromPi;
        public String fromCustomer;
        public String fromBill;
        public long fromSeq;
        public String fromMaterial;
        public String fromBatch;
        public Long fromRev;
        public String toStatus;
        public String toFactory;
        public String toPi;
        public String toCustomer;
        public String toBill;
        public long toSeq;
        public String toMaterial;
        public long toRev;
    }

    record Moved(
            Set<List<String>> toPush,
            List<Map<String, Object>> out,
            List<Map<String, Object>> in,
            List<String> xoOrgs) {}

    private Moved move(
            CurrentUser cu,
            String kind,
            long transferId,
            String docNo,
            List<Map<String, Object>> items,
            Map<Long, Target> targets) {
        LocalDateTime now = Util.now();
        String tkind = KIND_BILL.equals(kind) ? BILL_TRANSFER : ORG_TRANSFER;
        Set<String> mes = factories.mesCodes();
        Map<String, List<Map<String, Object>>> xo = new LinkedHashMap<>();
        Set<List<String>> toPush = new LinkedHashSet<>();
        List<TransferItemRow> rows = new ArrayList<>();
        Map<MoveGroup, List<Long>> groups = new LinkedHashMap<>();
        Map<String, Long> lineMoves = new LinkedHashMap<>();
        Map<String, String[]> lineKeys = new HashMap<>();
        TreeMap<Long, List<Long>> importLines = new TreeMap<>();
        for (Map<String, Object> r : items) {
            long id = R.l(r, "id");
            Target t = targets.get(id);
            String oldOrg = R.s(r, "factory_code");
            String newOrg = t.factory();
            long rev = R.l(r, "rev") + 1;
            String status = R.s(r, "status");
            String pushType = R.s(r, "push_type");
            String mode = R.s(r, "delivery_mode");
            String holder = R.s(r, "mes_holder");
            if (Objects.equals(newOrg, oldOrg)) {
                if (Objects.equals(holder, oldOrg) && mes.contains(oldOrg)) {
                    status = Util.TO_PUSH;
                    pushType = Util.PUSH_REASSIGN;
                    mode = null;
                }
            } else if (mes.contains(newOrg)) {
                status = Util.TO_PUSH;
                mode = null;
                pushType = Objects.equals(holder, oldOrg) ? Util.PUSH_REASSIGN : Util.PUSH_NEW;
            } else {
                status = Util.ALLOCATED;
                pushType = Util.PUSH_NEW;
                mode = null;
            }
            if (Util.TO_PUSH.equals(status)) {
                toPush.add(List.of(newOrg, t.pi()));
            }
            if (holder != null && holder.equals(oldOrg) && !Objects.equals(newOrg, oldOrg)) {
                Map<String, Object> e = new HashMap<>();
                e.put("item_id", id);
                e.put("sn", R.s(r, "sn"));
                e.put("rev", rev);
                e.put("pi_no", R.s(r, "pi_no"));
                e.put("bill_no", R.s(r, "bill_no"));
                e.put("bill_seq", lineOf(r));
                e.put("material_code", R.s(r, "material_code"));
                e.put("customer_code", R.s(r, "customer_code"));
                e.put("seq_pi_no", R.s(r, "seq_pi_no"));
                e.put("seq_dec", R.lo(r, "seq_dec"));
                e.put("length_text", R.s(r, "length_text"));
                e.put("attach_no", R.s(r, "attach_no"));
                e.put("rule_version_id", R.lo(r, "rule_version_id"));
                e.put("to_factory", newOrg);
                e.put("transfer_kind", tkind);
                e.put("transfer_doc_no", docNo);
                xo.computeIfAbsent(oldOrg, k -> new ArrayList<>()).add(e);
            }
            String fromOrg = Objects.equals(holder, oldOrg) && holder != null ? oldOrg : null;
            boolean keepFrom = fromOrg == null && Objects.equals(newOrg, oldOrg);
            long seq = isImport(r) ? 0 : t.seq();
            MoveGroup g =
                    new MoveGroup(
                            newOrg,
                            t.pi(),
                            t.customer(),
                            t.bill(),
                            seq,
                            t.material(),
                            status,
                            pushType,
                            mode,
                            fromOrg,
                            keepFrom);
            groups.computeIfAbsent(g, k -> new ArrayList<>()).add(id);
            if (isImport(r)) {
                importLines.computeIfAbsent(t.seq(), k -> new ArrayList<>()).add(R.l(r, "seq_dec"));
            } else if (!(t.bill().equals(R.s(r, "bill_no")) && t.seq() == R.l(r, "bill_seq"))) {
                String from = R.key(R.s(r, "bill_no"), R.i(r, "bill_seq"));
                String to = R.key(t.bill(), (int) t.seq());
                lineKeys.put(
                        from, new String[] {R.s(r, "bill_no"), String.valueOf(R.l(r, "bill_seq"))});
                lineKeys.put(to, new String[] {t.bill(), String.valueOf(t.seq())});
                lineMoves.merge(from, -1L, Long::sum);
                lineMoves.merge(to, 1L, Long::sum);
            }
            TransferItemRow x = new TransferItemRow();
            x.kind = kind;
            x.transferId = transferId;
            x.itemId = id;
            x.sn = R.s(r, "sn");
            x.seqPiNo = R.s(r, "seq_pi_no");
            x.seqDec = R.lo(r, "seq_dec");
            x.fromStatus = R.s(r, "status");
            x.fromFactory = oldOrg;
            x.fromPi = R.s(r, "pi_no");
            x.fromCustomer = R.s(r, "customer_code");
            x.fromBill = R.s(r, "bill_no");
            x.fromSeq = lineOf(r);
            x.fromMaterial = R.s(r, "material_code");
            x.fromBatch = R.s(r, "delivery_batch_no");
            x.fromRev = R.lo(r, "rev");
            x.toStatus = status;
            x.toFactory = newOrg;
            x.toPi = t.pi();
            x.toCustomer = t.customer();
            x.toBill = t.bill();
            x.toSeq = t.seq();
            x.toMaterial = t.material();
            x.toRev = rev;
            rows.add(x);
        }
        List<PiBillVO> touched = new ArrayList<>();
        items.forEach(r -> touched.add(new PiBillVO(R.s(r, "pi_no"), R.s(r, "bill_no"))));
        targets.values().forEach(t -> touched.add(new PiBillVO(t.pi(), t.bill())));
        billStats.touchMany(touched);
        for (Map.Entry<MoveGroup, List<Long>> e : groups.entrySet()) {
            for (List<Long> part : R.chunks(e.getValue(), 1000)) {
                mapper.moveItems(e.getKey(), tkind, docNo, now, part);
            }
        }
        for (Map.Entry<Long, List<Long>> e : importLines.entrySet()) {
            for (List<Long> part : R.chunks(e.getValue(), 1000)) {
                mapper.importSeq(e.getKey(), part);
            }
        }
        for (List<TransferItemRow> part : R.chunks(rows, 1000)) {
            mapper.insertTransferItems(part);
        }
        for (Map.Entry<String, Long> e : lineMoves.entrySet()) {
            if (e.getValue() != 0) {
                String[] k = lineKeys.get(e.getKey());
                long seq = Long.parseLong(k[1]);
                mapper.ensureLineCount(k[0], seq, now);
                mapper.addLineCount(k[0], seq, e.getValue(), now);
            }
        }
        List<Map<String, Object>> outBatches = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : xo.entrySet()) {
            List<Map<String, Object>> bs =
                    delivery.buildTransferOut(cu, e.getKey(), e.getValue(), null);
            outBatches.addAll(bs);
            for (Map<String, Object> b : bs) {
                mapper.setOutBatch(R.l(b, "id"), R.s(b, "batch_no"), kind, transferId);
            }
        }
        Set<String> bills = new TreeSet<>();
        items.forEach(r -> bills.add(R.s(r, "bill_no")));
        targets.values().forEach(t -> bills.add(t.bill()));
        changes.refreshItems(bills, false);
        TreeMap<String, List<Long>> held = new TreeMap<>();
        Map<String, String[]> heldKeys = new HashMap<>();
        for (Map.Entry<MoveGroup, List<Long>> e : groups.entrySet()) {
            MoveGroup g = e.getKey();
            if (Util.TO_PUSH.equals(g.status) && g.fromOrg != null && mes.contains(g.org)) {
                String k = g.org + "\u0000" + g.pi;
                heldKeys.put(k, new String[] {g.org, g.pi});
                held.computeIfAbsent(k, x -> new ArrayList<>()).addAll(e.getValue());
            }
        }
        List<Map<String, Object>> inBatches = new ArrayList<>();
        for (Map.Entry<String, List<Long>> e : held.entrySet()) {
            String[] k = heldKeys.get(e.getKey());
            inBatches.addAll(
                    delivery.buildDeliveryNow(cu, k[0], k[1], e.getValue(), tkind, docNo, null));
        }
        return new Moved(
                toPush, outBatches, inBatches, new ArrayList<>(new TreeSet<>(xo.keySet())));
    }

    private void afterMove(CurrentUser cu, Moved moved) {
        List<List<String>> pushes = new ArrayList<>(moved.toPush());
        pushes.sort(
                Comparator.comparing((List<String> x) -> x.get(0)).thenComparing(x -> x.get(1)));
        for (List<String> p : pushes) {
            delivery.startTask(cu, p.get(0), p.get(1));
        }
        for (String org : moved.xoOrgs()) {
            delivery.startTask(cu, org, BatchServiceImpl.XO_KEY);
        }
    }

    // ---------------------------------------------------------------- 来源与目标
    /** 来源中选中的一组号：物料编码 + 附件项次（同级）及转移方式。 */
    record Pick(
            String material,
            String attach,
            String mode,
            String startSn,
            String endSn,
            int qty,
            Integer toSeq) {
        boolean matches(Map<String, Object> r) {
            return Objects.equals(R.s(r, "material_code"), material)
                    && (attach == null || attachOf(r).equals(attach));
        }

        boolean overlaps(Pick o) {
            return material.equals(o.material)
                    && (attach == null || o.attach == null || attach.equals(o.attach));
        }

        String text() {
            String s = material.isEmpty() ? "(空物料编码)" : material;
            if (attach != null) {
                s += "（附件项次 " + (attach.isEmpty() ? "空" : attach) + "）";
            }
            if (PICK_RANGE.equals(mode)) {
                s += " " + (startSn.equals(endSn) ? startSn : startSn + " ~ " + endSn);
            } else if (PICK_QTY.equals(mode)) {
                s += " " + qty + " 枚";
            }
            return s;
        }
    }

    record Source(String pi, String bill, String scope, List<Pick> picks) {
        Pick singleRange() {
            if (!SCOPE_BILL.equals(scope)
                    && picks.size() == 1
                    && PICK_RANGE.equals(picks.get(0).mode())) {
                return picks.get(0);
            }
            return null;
        }

        String docScope() {
            if (SCOPE_BILL.equals(scope)) {
                return SCOPE_BILL;
            }
            return singleRange() != null ? SCOPE_RANGE : SCOPE_MATERIAL;
        }

        String material() {
            if (SCOPE_BILL.equals(scope)) {
                return "";
            }
            return Util.cut(
                    String.join(
                            "、", new LinkedHashSet<>(picks.stream().map(Pick::material).toList())),
                    64);
        }

        String startSn() {
            Pick p = singleRange();
            return p != null ? p.startSn() : "";
        }

        String endSn() {
            Pick p = singleRange();
            return p != null ? p.endSn() : "";
        }

        String text(String factory) {
            List<String> parts = new ArrayList<>();
            for (String x : new String[] {factory, pi, bill}) {
                if (x != null && !x.isEmpty()) {
                    parts.add(x);
                }
            }
            if (!SCOPE_BILL.equals(scope)) {
                picks.forEach(p -> parts.add(p.text()));
            }
            return String.join(" / ", parts);
        }
    }

    record Dest(
            String factory,
            String pi,
            String customer,
            String bill,
            List<Map<String, Object>> lines) {}

    private static Pick pickOf(String scope, TransferLineDTO s) {
        if (s.materialCode == null) {
            throw Biz.err(SnErrorCode.TR_MATERIAL_REQUIRED);
        }
        String material = s.materialCode.strip();
        String attach = s.attachNo == null ? null : s.attachNo.strip();
        if (SCOPE_BILL.equals(scope)) {
            return new Pick(material, attach, PICK_ALL, "", "", 0, s.toSeq);
        }
        String mode;
        if (SCOPE_RANGE.equals(scope)) {
            mode = PICK_RANGE;
        } else {
            mode = Util.trim(s.mode).toUpperCase();
            if (mode.isEmpty()) {
                mode =
                        !Util.trim(s.startSn).isEmpty()
                                ? PICK_RANGE
                                : s.qty != null ? PICK_QTY : PICK_ALL;
            }
        }
        if (!PICK_MODES.contains(mode)) {
            throw Biz.err(SnErrorCode.TR_LINE_MODE_INVALID);
        }
        String start = "";
        String end = "";
        int qty = 0;
        if (PICK_RANGE.equals(mode)) {
            start = Util.trim(s.startSn);
            if (start.isEmpty()) {
                throw Biz.err(SnErrorCode.TR_SN_REQUIRED);
            }
            end = Util.trim(s.endSn).isEmpty() ? start : Util.trim(s.endSn);
        } else if (PICK_QTY.equals(mode)) {
            if (s.qty == null || s.qty < 1) {
                throw Biz.err(SnErrorCode.TR_QTY_INVALID);
            }
            qty = s.qty;
        }
        return new Pick(material, attach, mode, start, end, qty, s.toSeq);
    }

    private static Source source(TransferDTO r) {
        String pi = Util.trim(r.piNo);
        if (pi.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        String bill = Util.trim(r.billNo);
        if (bill.isEmpty()) {
            throw Biz.err(SnErrorCode.ORDER_BILL_REQUIRED);
        }
        String scope = Util.trim(r.scope);
        if (!SCOPES.contains(scope)) {
            throw Biz.err(SnErrorCode.TR_SCOPE_INVALID);
        }
        List<TransferLineDTO> specs =
                r.lines == null ? new ArrayList<>() : new ArrayList<>(r.lines);
        if (specs.isEmpty() && !SCOPE_BILL.equals(scope)) {
            TransferLineDTO s = new TransferLineDTO();
            s.materialCode = r.materialCode;
            s.attachNo = r.attachNo;
            s.startSn = r.startSn;
            s.endSn = r.endSn;
            s.qty = r.qty;
            specs.add(s);
        }
        List<Pick> picks = new ArrayList<>();
        for (TransferLineDTO s : specs) {
            Pick p = pickOf(scope, s);
            for (Pick o : picks) {
                if (p.overlaps(o)) {
                    throw Biz.err(
                            SnErrorCode.TR_LINE_DUPLICATE,
                            "material",
                            p.material().isEmpty() ? "(空)" : p.material(),
                            "attach_no",
                            p.attach() == null ? "-" : p.attach());
                }
            }
            picks.add(p);
        }
        if (SCOPE_RANGE.equals(scope) && picks.size() != 1) {
            throw Biz.err(SnErrorCode.TR_SCOPE_INVALID);
        }
        return new Source(pi, bill, scope, picks);
    }

    private List<Map<String, Object>> sourceItems(Source src, String factory, boolean lock) {
        List<String> mats = null;
        if (!SCOPE_BILL.equals(src.scope())) {
            mats =
                    new ArrayList<>(
                            new TreeSet<>(src.picks().stream().map(Pick::material).toList()));
        }
        List<Map<String, Object>> items =
                withImportLines(mapper.sourceItems(src.pi(), src.bill(), factory, mats, lock));
        if (SCOPE_BILL.equals(src.scope())) {
            return items;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pick p : src.picks()) {
            List<Map<String, Object>> g =
                    new ArrayList<>(items.stream().filter(p::matches).toList());
            if (PICK_RANGE.equals(p.mode())) {
                g = slice(g, p.startSn(), p.endSn());
            } else if (PICK_QTY.equals(p.mode())) {
                Map<Long, String> bad = blocked(g);
                List<Map<String, Object>> ok =
                        g.stream().filter(r -> !bad.containsKey(R.l(r, "id"))).toList();
                if (ok.size() < p.qty()) {
                    throw Biz.err(
                            SnErrorCode.TR_QTY_EXCEEDS,
                            "material",
                            p.material().isEmpty() ? "(空)" : p.material(),
                            "attach_no",
                            p.attach() == null ? "-" : p.attach(),
                            "qty",
                            p.qty(),
                            "available",
                            ok.size());
                }
                g = new ArrayList<>(ok.subList(0, p.qty()));
            }
            if (g.isEmpty()) {
                throw Biz.err(
                        SnErrorCode.TR_LINE_EMPTY,
                        "material",
                        p.material().isEmpty() ? "(空)" : p.material(),
                        "attach_no",
                        p.attach() == null ? "-" : p.attach());
            }
            out.addAll(g);
        }
        out.sort(
                Comparator.comparing((Map<String, Object> r) -> R.sz(r, "seq_pi_no"))
                        .thenComparingLong(r -> R.l(r, "seq_dec")));
        return out;
    }

    private static List<Map<String, Object>> slice(
            List<Map<String, Object>> items, String start, String end) {
        Map<String, Map<String, Object>> bySn = new HashMap<>();
        items.forEach(r -> bySn.put(R.s(r, "sn"), r));
        Map<String, Object> s = bySn.get(start);
        Map<String, Object> e = bySn.get(end);
        if (s == null || e == null || !Objects.equals(R.s(s, "seq_pi_no"), R.s(e, "seq_pi_no"))) {
            throw Biz.err(SnErrorCode.TR_RANGE_INVALID, "start_sn", start, "end_sn", end);
        }
        long lo = Math.min(R.l(s, "seq_dec"), R.l(e, "seq_dec"));
        long hi = Math.max(R.l(s, "seq_dec"), R.l(e, "seq_dec"));
        String sp = R.s(s, "seq_pi_no");
        return new ArrayList<>(
                items.stream()
                        .filter(
                                r ->
                                        Objects.equals(R.s(r, "seq_pi_no"), sp)
                                                && R.l(r, "seq_dec") >= lo
                                                && R.l(r, "seq_dec") <= hi)
                        .toList());
    }

    private Dest dest(String kind, TransferDTO r, String sourceBill) {
        String toPi = Util.trim(r.toPi);
        if (toPi.isEmpty()) {
            throw Biz.err(SnErrorCode.TR_TO_PI_REQUIRED);
        }
        String toBill = Util.trim(r.toBill);
        if (toBill.isEmpty()) {
            throw Biz.err(SnErrorCode.TR_TO_BILL_REQUIRED);
        }
        List<Map<String, Object>> ls = orders.lines(toBill);
        Map<String, Object> h = ls.isEmpty() ? null : ls.get(0);
        String tbPi = h == null ? null : R.s(h, "pi");
        if (h == null
                || tbPi == null
                || tbPi.isEmpty()
                || (KIND_BILL.equals(kind) && toBill.equals(sourceBill))) {
            throw Biz.err(SnErrorCode.TR_TARGET_BILL_INVALID, "bill_no", toBill);
        }
        if (!tbPi.equals(toPi)) {
            throw Biz.err(SnErrorCode.TR_TARGET_PI_MISMATCH, "bill_no", toBill, "pi", tbPi);
        }
        String toFactory = Util.trim(r.toFactory);
        if (toFactory.isEmpty()) {
            throw Biz.err(SnErrorCode.TR_TO_FACTORY_REQUIRED);
        }
        if (KIND_BILL.equals(kind)) {
            if (!toFactory.equals(R.s(h, "org_code"))) {
                throw Biz.err(
                        SnErrorCode.TR_TARGET_ORG_MISMATCH,
                        "bill_no",
                        toBill,
                        "factory",
                        R.s(h, "org_code"));
            }
        } else {
            toFactory = factories.mustBeEnabled(toFactory).factoryCode;
        }
        return new Dest(
                toFactory,
                tbPi,
                R.s(h, "customer_code"),
                R.s(h, "bill_no"),
                orders.effectiveLines(ls));
    }

    private static RuntimeException notMatch(Dest d, String material, String attach) {
        return Biz.err(
                SnErrorCode.TR_TARGET_LINE_NOT_MATCH,
                "bill_no",
                d.bill(),
                "material",
                material == null || material.isEmpty() ? "(空)" : material,
                "attach_no",
                attach == null || attach.isEmpty() ? "-" : attach);
    }

    private static Map<String, Object> destLine(
            Dest d,
            Source src,
            TransferDTO r,
            String material,
            String attach,
            long fromSeq,
            List<Map<String, Object>> rs) {
        String toMat = r.toMaterial == null ? null : r.toMaterial.strip();
        if (toMat != null && !toMat.equals(material)) {
            throw notMatch(d, material, attach);
        }
        Pick pick = src.picks().stream().filter(p -> p.matches(rs.get(0))).findFirst().orElse(null);
        Integer want = pick != null && pick.toSeq() != null ? pick.toSeq() : r.toSeq;
        List<Map<String, Object>> cands =
                d.lines().stream()
                        .filter(
                                x ->
                                        Objects.equals(R.s(x, "material_code"), material)
                                                && Objects.equals(R.s(x, "eff_attach"), attach))
                        .toList();
        if (want != null) {
            Map<String, Object> line =
                    d.lines().stream().filter(x -> R.l(x, "seq") == want).findFirst().orElse(null);
            if (line == null) {
                throw Biz.err(SnErrorCode.TR_TARGET_LINE_INVALID, "seq", want);
            }
            if (!cands.contains(line)) {
                throw notMatch(d, material, attach);
            }
            return line;
        }
        if (cands.isEmpty()) {
            throw notMatch(d, material, attach);
        }
        if (d.bill().equals(R.s(rs.get(0), "bill_no"))) {
            for (Map<String, Object> x : cands) {
                if (R.l(x, "seq") == fromSeq) {
                    return x;
                }
            }
        }
        Set<String> lens = new TreeSet<>();
        for (Map<String, Object> x : rs) {
            if (x.get("length_text") != null) {
                lens.add(R.s(x, "length_text"));
            }
        }
        List<Map<String, Object>> fit =
                cands.stream()
                        .filter(x -> lens.stream().allMatch(v -> v.equals(R.s(x, "eff_length"))))
                        .toList();
        if (fit.size() == 1) {
            return fit.get(0);
        }
        if (fit.isEmpty() && cands.size() == 1) {
            return cands.get(0);
        }
        if (fit.isEmpty()) {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            cands.forEach(
                    x -> {
                        String v = R.s(x, "eff_length");
                        targets.add(v == null || v.isEmpty() ? "(空)" : v);
                    });
            throw Biz.err(
                    SnErrorCode.TR_LENGTH_MISMATCH,
                    "target",
                    String.join("、", targets),
                    "current",
                    String.join("、", lens));
        }
        throw Biz.err(
                SnErrorCode.TR_TARGET_LINE_REQUIRED,
                "bill_no",
                d.bill(),
                "material",
                material == null || material.isEmpty() ? "(空)" : material,
                "attach_no",
                attach == null || attach.isEmpty() ? "-" : attach);
    }

    record Mapping(
            String material,
            String attach,
            long fromSeq,
            long toSeq,
            String length,
            List<Long> ids) {}

    record Targets(Map<Long, Target> targets, List<Mapping> mapping) {}

    private static Targets targets(
            List<Map<String, Object>> items, Dest d, Source src, TransferDTO r) {
        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> x : items) {
            if (isImport(x) && x.get("import_seq") == null) {
                continue;
            }
            groups.computeIfAbsent(
                            Arrays.asList(R.s(x, "material_code"), attachOf(x), lineOf(x)),
                            k -> new ArrayList<>())
                    .add(x);
        }
        Map<Long, Target> out = new HashMap<>();
        List<Mapping> mapping = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Map<String, Object>>> e : groups.entrySet()) {
            String material = (String) e.getKey().get(0);
            String attach = (String) e.getKey().get(1);
            long fromSeq = (Long) e.getKey().get(2);
            List<Map<String, Object>> rs = e.getValue();
            Map<String, Object> line = destLine(d, src, r, material, attach, fromSeq, rs);
            String effLength = R.s(line, "eff_length");
            for (Map<String, Object> x : rs) {
                String lt = R.s(x, "length_text");
                if (lt != null && !lt.equals(effLength)) {
                    throw Biz.err(
                            SnErrorCode.TR_LENGTH_MISMATCH,
                            "target",
                            effLength == null || effLength.isEmpty() ? "(空)" : effLength,
                            "current",
                            lt);
                }
                out.put(
                        R.l(x, "id"),
                        new Target(
                                d.factory(),
                                d.pi(),
                                d.customer(),
                                d.bill(),
                                R.l(line, "seq"),
                                R.s(line, "material_code")));
            }
            mapping.add(
                    new Mapping(
                            material,
                            attach,
                            fromSeq,
                            R.l(line, "seq"),
                            effLength,
                            rs.stream().map(x -> R.l(x, "id")).toList()));
        }
        mapping.sort(
                Comparator.comparing((Mapping m) -> m.material() == null ? "" : m.material())
                        .thenComparing(Mapping::attach)
                        .thenComparingLong(Mapping::fromSeq));
        return new Targets(out, mapping);
    }

    private String fromFactory(CurrentUser cu, String code) {
        if (cu.isAdmin()) {
            return factories.mustGet(code).factoryCode;
        }
        return cu.writeFactory(code);
    }

    record Plan(
            String factory,
            Source src,
            Dest dest,
            List<Map<String, Object>> items,
            Map<Long, Target> targets,
            List<Mapping> mapping) {
        Long toSeq() {
            Set<Long> seqs = new HashSet<>(mapping.stream().map(Mapping::toSeq).toList());
            return seqs.size() == 1 ? seqs.iterator().next() : null;
        }

        String toMaterial() {
            return Util.cut(
                    String.join(
                            "、",
                            new LinkedHashSet<>(mapping.stream().map(Mapping::material).toList())),
                    64);
        }

        String toText() {
            String seqs =
                    mapping.stream()
                            .map(
                                    m ->
                                            m.material()
                                                    + "#"
                                                    + m.attach()
                                                    + ":"
                                                    + m.fromSeq()
                                                    + "->"
                                                    + m.toSeq())
                            .collect(Collectors.joining(","));
            return dest.bill() + "/" + seqs + "/" + dest.pi() + "/" + dest.factory();
        }
    }

    private Plan plan(CurrentUser cu, String kind, TransferDTO r, boolean lock) {
        String factory =
                KIND_ORG.equals(kind) || !Util.trim(r.factoryCode).isEmpty()
                        ? fromFactory(cu, r.factoryCode)
                        : null;
        Source src = source(r);
        Dest d = dest(kind, r, src.bill());
        List<Map<String, Object>> items = sourceItems(src, factory, lock);
        if (items.isEmpty()) {
            throw Biz.err(SnErrorCode.TR_NOTHING);
        }
        Targets ts = targets(items, d, src, r);
        if (KIND_BILL.equals(kind)) {
            cu.checkWrite(
                    items.stream()
                            .map(x -> R.s(x, "factory_code"))
                            .distinct()
                            .toArray(String[]::new));
        }
        List<Map<String, Object>> movable =
                items.stream().filter(x -> ts.targets().containsKey(R.l(x, "id"))).toList();
        if (!movable.isEmpty()
                && movable.stream()
                        .allMatch(
                                x -> {
                                    String f = R.s(x, "factory_code");
                                    return Objects.equals(f == null ? d.factory() : f, d.factory())
                                            && Objects.equals(R.s(x, "pi_no"), d.pi())
                                            && Objects.equals(R.s(x, "bill_no"), d.bill())
                                            && lineOf(x) == ts.targets().get(R.l(x, "id")).seq();
                                })) {
            throw Biz.err(SnErrorCode.TR_SAME_TARGET);
        }
        return new Plan(factory, src, d, items, ts.targets(), ts.mapping());
    }

    /** 目标单据与来源单据不同、且目标生产组织就是目标单据的生产组织 → 转单；其余 → 转组织。 */
    String decideKind(TransferDTO r) {
        String toBill = Util.trim(r.toBill);
        if (!toBill.isEmpty()) {
            List<Map<String, Object>> ls = orders.lines(toBill);
            if (!ls.isEmpty()) {
                Map<String, Object> h = ls.get(0);
                if (!R.s(h, "bill_no").equals(Util.trim(r.billNo))
                        && Util.trim(r.toFactory).equals(R.s(h, "org_code"))) {
                    return KIND_BILL;
                }
            }
        }
        return KIND_ORG;
    }

    @Override
    public Map<String, Object> sources(String kind, String factory, String pi) {
        CurrentUser cu = cu();
        String p = Util.trim(pi);
        if (p.isEmpty()) {
            throw Biz.err(SnErrorCode.PI_REQUIRED);
        }
        String f = null;
        List<String> scope = null;
        if (KIND_ORG.equals(kind)) {
            f = fromFactory(cu, factory);
        } else {
            scope = cu.readFactories(null);
        }
        Map<String, Map<String, Object>> bills = new LinkedHashMap<>();
        Map<String, Map<String, Object>> mats = new HashMap<>();
        Map<String, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> x : mapper.sources(p, f, scope)) {
            long n = R.l(x, "n");
            String attach = R.sz(x, "attach_no");
            String billNo = R.s(x, "bill_no");
            Map<String, Object> b =
                    bills.computeIfAbsent(
                            billNo,
                            k ->
                                    R.of(
                                            "billNo",
                                            billNo,
                                            "qty",
                                            0L,
                                            "materials",
                                            new ArrayList<>(),
                                            "rows",
                                            new ArrayList<>()));
            b.put("qty", (Long) b.get("qty") + n);
            String mk = billNo + "\u0000" + R.s(x, "material_code") + "\u0000" + attach;
            Map<String, Object> mm = mats.get(mk);
            if (mm == null) {
                mm = R.of("materialCode", R.s(x, "material_code"), "attachNo", attach, "qty", 0L);
                mats.put(mk, mm);
                listOf(b, "materials").add(mm);
            }
            mm.put("qty", (Long) mm.get("qty") + n);
            String rk = mk + "\u0000" + R.s(x, "factory_code");
            Map<String, Object> row = rows.get(rk);
            if (row == null) {
                row =
                        R.of(
                                "materialCode",
                                R.s(x, "material_code"),
                                "attachNo",
                                attach,
                                "factoryCode",
                                R.s(x, "factory_code"),
                                "qty",
                                0L,
                                "customers",
                                new ArrayList<>(),
                                "attachNos",
                                new ArrayList<>(),
                                "lengths",
                                new ArrayList<>(),
                                "seqs",
                                new ArrayList<>());
                rows.put(rk, row);
                listOf(b, "rows").add(row);
            }
            row.put("qty", (Long) row.get("qty") + n);
            addOnce(row, "customers", R.s(x, "customer_code"));
            addOnce(row, "attachNos", R.s(x, "attach_no"));
            addOnce(row, "lengths", R.s(x, "length_text"));
            addOnce(row, "seqs", R.l(x, "line_seq"));
        }
        for (Map<String, Object> row : rows.values()) {
            @SuppressWarnings("unchecked")
            List<Long> seqs = (List<Long>) row.get("seqs");
            seqs.sort(null);
        }
        return R.of("bills", new ArrayList<>(bills.values()));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Map<String, Object> m, String k) {
        return (List<Object>) m.get(k);
    }

    private static void addOnce(Map<String, Object> row, String k, Object v) {
        List<Object> l = listOf(row, k);
        if (v != null && !l.contains(v)) {
            l.add(v);
        }
    }

    @Override
    public Map<String, Object> locate(String q) {
        CurrentUser cu = cu();
        String text = Util.trim(q);
        if (text.isEmpty()) {
            throw Biz.err(SnErrorCode.TR_LOCATE_NOT_FOUND, "q", "");
        }
        List<String> scope = cu.readFactories(null);
        Map<String, Object> r = mapper.locateSn(text, scope);
        if (r != null) {
            if (Util.PENDING_ALLOC.equals(R.s(r, "status"))) {
                throw Biz.err(
                        SnErrorCode.TR_SN_NOT_TRANSFERABLE,
                        "sn",
                        R.s(r, "sn"),
                        "reason",
                        REASON_TEXT.get("PENDING_ALLOC"));
            }
            return R.of(
                    "by", "SN",
                    "pi", R.s(r, "pi_no"),
                    "billNo", R.s(r, "bill_no"),
                    "materialCode", R.s(r, "material_code"),
                    "attachNo", R.sz(r, "attach_no"),
                    "factoryCode", R.s(r, "factory_code"),
                    "sn", R.s(r, "sn"));
        }
        String pi = mapper.locateBill(text, scope);
        if (pi != null) {
            return R.of("by", "BILL", "pi", pi, "billNo", text);
        }
        if (mapper.locatePi(text, scope)) {
            return R.of("by", "PI", "pi", text);
        }
        throw Biz.err(SnErrorCode.TR_LOCATE_NOT_FOUND, "q", text);
    }

    private Map<String, Object> preview(CurrentUser cu, String kind, TransferDTO r) {
        Plan p = plan(cu, kind, r, false);
        Map<Long, String> bad = blocked(p.items());
        Map<String, Object> out = summary(p.items(), bad);
        Map<String, Long> moves = new TreeMap<>();
        Map<String, Map<String, Object>> keys = new HashMap<>();
        List<Map<String, Object>> ok =
                p.items().stream().filter(x -> !bad.containsKey(R.l(x, "id"))).toList();
        for (Map<String, Object> x : ok) {
            Target t = p.targets().get(R.l(x, "id"));
            if (!isImport(x)
                    && !(t.bill().equals(R.s(x, "bill_no")) && t.seq() == R.l(x, "bill_seq"))) {
                String from = R.key(R.s(x, "bill_no"), R.i(x, "bill_seq"));
                String to = R.key(t.bill(), (int) t.seq());
                keys.put(from, R.of("bill_no", R.s(x, "bill_no"), "seq", R.l(x, "bill_seq")));
                keys.put(to, R.of("bill_no", t.bill(), "seq", t.seq()));
                moves.merge(from, -1L, Long::sum);
                moves.merge(to, 1L, Long::sum);
            }
        }
        Map<String, Map<String, Object>> counts = orders.lineCounts(keys.values());
        List<Map<String, Object>> changesOut = new ArrayList<>();
        List<String> sortedKeys = new ArrayList<>(moves.keySet());
        sortedKeys.sort(
                Comparator.comparing((String k) -> R.s(keys.get(k), "bill_no"))
                        .thenComparingLong(k -> R.l(keys.get(k), "seq")));
        for (String k : sortedKeys) {
            Map<String, Object> c = counts.get(k);
            long before = c == null ? 0 : R.l(c, "gen_count");
            changesOut.add(
                    R.of(
                            "billNo",
                            R.s(keys.get(k), "bill_no"),
                            "seq",
                            R.l(keys.get(k), "seq"),
                            "before",
                            before,
                            "after",
                            before + moves.get(k)));
        }
        out.put("generatedChanges", changesOut);
        Dest d = p.dest();
        out.put("toPi", d.pi());
        out.put("toOrg", d.factory());
        out.put("toBill", d.bill());
        out.put("toSeq", p.toSeq());
        out.put("toMaterial", p.toMaterial());
        out.put("toCustomer", d.customer());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Mapping mp : p.mapping()) {
            lines.add(
                    R.of(
                            "materialCode", mp.material(),
                            "attachNo", mp.attach(),
                            "fromSeq", mp.fromSeq(),
                            "toSeq", mp.toSeq(),
                            "length", mp.length(),
                            "total", mp.ids().size(),
                            "transferable",
                                    mp.ids().stream().filter(i -> !bad.containsKey(i)).count()));
        }
        out.put("lines", lines);
        out.put("scope", p.src().text(p.factory()));
        out.put(
                "otherOrg",
                p.items().stream()
                        .filter(
                                x ->
                                        x.get("factory_code") != null
                                                && !R.s(x, "factory_code").equals(d.factory()))
                        .count());
        out.put(
                "xoQty",
                ok.stream()
                        .filter(
                                x ->
                                        x.get("mes_holder") != null
                                                && R.s(x, "mes_holder")
                                                        .equals(R.s(x, "factory_code"))
                                                && !R.s(x, "factory_code").equals(d.factory()))
                        .count());
        return out;
    }

    @Override
    public Map<String, Object> preview(String kind, TransferDTO r) {
        return preview(cu(), kind, r);
    }

    @Override
    public Map<String, Object> previewAuto(TransferDTO r) {
        CurrentUser cu = cu();
        String kind = decideKind(r);
        Map<String, Object> out = preview(cu, kind, r);
        out.put("kind", kind);
        return out;
    }

    @Override
    public Map<String, Object> transfer(TransferDTO r) {
        String kind = decideKind(r);
        Map<String, Object> doc = KIND_BILL.equals(kind) ? transferBill(r) : direct(r);
        return R.of("kind", kind, "doc", doc);
    }

    private static String nos(List<Map<String, Object>> bs) {
        return bs.stream().map(b -> R.s(b, "batch_no")).collect(Collectors.joining(","));
    }

    // ---------------------------------------------------------------- 转单
    @Override
    public Map<String, Object> transferBill(TransferDTO req) {
        CurrentUser cu = cu();
        String reason = reason(req.reason);
        Plan p0 = plan(cu, KIND_BILL, req, false);
        Object[] res =
                locks.withPiLock(
                        () ->
                                tx.run(
                                        () -> {
                                            Plan p = plan(cu, KIND_BILL, req, true);
                                            Map<Long, String> bad = blocked(p.items());
                                            List<Map<String, Object>> ok =
                                                    p.items().stream()
                                                            .filter(
                                                                    x ->
                                                                            !bad.containsKey(
                                                                                    R.l(x, "id")))
                                                            .toList();
                                            checkAny(p.items(), ok, bad, p.src());
                                            Source src = p.src();
                                            Dest d = p.dest();
                                            String scope = src.text(null);
                                            String no = Util.nextId("TB");
                                            Map<String, Object> e = new HashMap<>();
                                            e.put("no", no);
                                            e.put(
                                                    "mode",
                                                    SCOPE_BILL.equals(src.scope())
                                                            ? MODE_VOID
                                                            : MODE_SPLIT);
                                            e.put("scope", src.docScope());
                                            e.put("fromBill", src.bill());
                                            e.put("toBill", d.bill());
                                            e.put("fromPi", src.pi());
                                            e.put("toPi", d.pi());
                                            e.put("toFactory", d.factory());
                                            e.put("toSeq", p.toSeq());
                                            e.put("toMaterial", p.toMaterial());
                                            e.put("scopeText", Util.cut(scope, 500));
                                            e.put("qty", ok.size());
                                            e.put("reason", reason);
                                            e.put("by", cu.empNo());
                                            e.put("now", Util.now());
                                            mapper.insertBillTransfer(e);
                                            long tid = ((Number) e.get("id")).longValue();
                                            Moved moved =
                                                    move(cu, KIND_BILL, tid, no, ok, p.targets());
                                            audit.record(
                                                    cu,
                                                    AuditEntryDTO.of(AuditAction.TRANSFER_BILL)
                                                            .factory(R.s(ok.get(0), "factory_code"))
                                                            .pi(src.pi())
                                                            .bill(src.bill())
                                                            .count(ok.size())
                                                            .range(
                                                                    R.s(ok.get(0), "sn"),
                                                                    R.s(
                                                                            ok.get(ok.size() - 1),
                                                                            "sn"))
                                                            .transfer(no)
                                                            .why(reason)
                                                            .info(
                                                                    "to="
                                                                            + p.toText()
                                                                            + " scope="
                                                                            + scope
                                                                            + " out_batches="
                                                                            + nos(moved.out())
                                                                            + " in_batches="
                                                                            + nos(moved.in())));
                                            return new Object[] {tid, moved};
                                        }),
                        p0.src().pi(),
                        p0.dest().pi());
        afterMove(cu, (Moved) res[1]);
        return billTransferView(cu, (Long) res[0]);
    }

    // ---------------------------------------------------------------- 转组织
    @Override
    public Map<String, Object> direct(TransferDTO r) {
        CurrentUser cu = cu();
        String reason = reason(r.reason);
        Plan p0 = plan(cu, KIND_ORG, r, false);
        Object[] res =
                locks.withPiLock(
                        () ->
                                tx.run(
                                        () -> {
                                            Plan p = plan(cu, KIND_ORG, r, true);
                                            Map<Long, String> bad = blocked(p.items());
                                            List<Map<String, Object>> ok =
                                                    p.items().stream()
                                                            .filter(
                                                                    x ->
                                                                            !bad.containsKey(
                                                                                    R.l(x, "id")))
                                                            .toList();
                                            checkAny(p.items(), ok, bad, p.src());
                                            Source src = p.src();
                                            Dest d = p.dest();
                                            String no = Util.nextId("TO");
                                            Map<String, Object> e = new HashMap<>();
                                            e.put("no", no);
                                            e.put("scope", src.docScope());
                                            e.put("fromFactory", p.factory());
                                            e.put("fromPi", src.pi());
                                            e.put("fromBill", src.bill());
                                            e.put("fromMaterial", src.material());
                                            e.put("fromSn", src.startSn());
                                            e.put("fromEndSn", src.endSn());
                                            e.put("qty", ok.size());
                                            e.put("toFactory", d.factory());
                                            e.put("toPi", d.pi());
                                            e.put("toCustomer", d.customer());
                                            e.put("toBill", d.bill());
                                            e.put("toSeq", p.toSeq());
                                            e.put("toMaterial", p.toMaterial());
                                            e.put("reason", reason);
                                            e.put("by", cu.empNo());
                                            e.put("now", Util.now());
                                            mapper.insertOrgTransfer(e);
                                            long tid = ((Number) e.get("id")).longValue();
                                            Moved moved =
                                                    move(cu, KIND_ORG, tid, no, ok, p.targets());
                                            String detail =
                                                    "scope=" + src.text(null) + " to=" + p.toText();
                                            audit.record(
                                                    cu,
                                                    AuditEntryDTO.of(AuditAction.TRANSFER_DIRECT)
                                                            .factory(p.factory())
                                                            .pi(src.pi())
                                                            .count(ok.size())
                                                            .transfer(no)
                                                            .why(reason)
                                                            .range(
                                                                    R.s(ok.get(0), "sn"),
                                                                    R.s(
                                                                            ok.get(ok.size() - 1),
                                                                            "sn"))
                                                            .info(
                                                                    detail
                                                                            + " out_batches="
                                                                            + nos(moved.out())
                                                                            + " in_batches="
                                                                            + nos(moved.in())));
                                            return new Object[] {tid, moved};
                                        }),
                        p0.src().pi(),
                        p0.dest().pi());
        afterMove(cu, (Moved) res[1]);
        return orgView(cu, (Long) res[0]);
    }

    // ---------------------------------------------------------------- 物料确认对应（F-06）
    /** 把失去对应行的已生成数、本单生成数并入指定新行，号改挂到新行；已交付给 MES 的号 rev + 1 以改挂重推（同一事务内当场建 REMAP 批次）。 */
    public void mapMaterial(
            CurrentUser cu, Map<String, Object> alert, Integer targetSeq, String note) {
        String bill = R.s(alert, "bill_no");
        long old = R.l(alert, "seq");
        Map<String, Object> line = targetSeq == null ? null : mapper.moLine(bill, targetSeq);
        if (line == null || targetSeq == old) {
            throw Biz.err(
                    SnErrorCode.ALERT_MAP_TARGET_INVALID,
                    "seq",
                    targetSeq == null ? "" : targetSeq);
        }
        long alertId = R.l(alert, "id");
        Set<List<String>> pushes =
                locks.withPiLock(
                        () ->
                                tx.run(
                                        () -> {
                                            Map<String, Object> a = mapper.orderAlertLock(alertId);
                                            if (a == null || !"OPEN".equals(R.s(a, "status"))) {
                                                throw Biz.err(SnErrorCode.ALERT_NOT_FOUND);
                                            }
                                            List<Map<String, Object>> items =
                                                    mapper.lineItems(bill, old);
                                            long frozen =
                                                    items.stream()
                                                            .filter(
                                                                    x ->
                                                                            Util.TO_PUSH.equals(
                                                                                            R.s(
                                                                                                    x,
                                                                                                    "status"))
                                                                                    && x.get(
                                                                                                    "delivery_batch_no")
                                                                                            != null)
                                                            .count();
                                            frozen +=
                                                    delivery.frozenByTransferOut(
                                                                    items.stream()
                                                                            .map(x -> R.l(x, "id"))
                                                                            .toList())
                                                            .size();
                                            if (frozen > 0) {
                                                throw Biz.err(
                                                        SnErrorCode.ALERT_MAP_FROZEN,
                                                        "count",
                                                        frozen);
                                            }
                                            Set<String> mes = factories.mesCodes();
                                            LocalDateTime now = Util.now();
                                            Set<List<String>> ps = new LinkedHashSet<>();
                                            Map<String, List<Long>> groups = new LinkedHashMap<>();
                                            groups.put("REASSIGN", new ArrayList<>());
                                            groups.put("DELIVERED", new ArrayList<>());
                                            groups.put("", new ArrayList<>());
                                            for (Map<String, Object> x : items) {
                                                String holder = R.s(x, "mes_holder");
                                                if (holder == null) {
                                                    groups.get("").add(R.l(x, "id"));
                                                } else if (holder.equals(R.s(x, "factory_code"))
                                                        && mes.contains(R.s(x, "factory_code"))) {
                                                    groups.get("REASSIGN").add(R.l(x, "id"));
                                                    ps.add(
                                                            List.of(
                                                                    R.s(x, "factory_code"),
                                                                    R.s(x, "pi_no")));
                                                } else {
                                                    groups.get("DELIVERED").add(R.l(x, "id"));
                                                }
                                            }
                                            Set<Long> reassign =
                                                    new HashSet<>(groups.get("REASSIGN"));
                                            billStats.touchMany(
                                                    items.stream()
                                                            .map(
                                                                    x ->
                                                                            new PiBillVO(
                                                                                    R.s(x, "pi_no"),
                                                                                    R.s(
                                                                                            x,
                                                                                            "bill_no")))
                                                            .toList());
                                            for (Map.Entry<String, List<Long>> g :
                                                    groups.entrySet()) {
                                                for (List<Long> part :
                                                        R.chunks(g.getValue(), 1000)) {
                                                    mapper.remapItems(
                                                            targetSeq,
                                                            R.s(line, "material_code"),
                                                            g.getKey(),
                                                            now,
                                                            part);
                                                }
                                            }
                                            Map<String, Object> oc =
                                                    mapper.lineCountLock(bill, old);
                                            if (oc != null) {
                                                mapper.mergeLineCount(
                                                        bill,
                                                        targetSeq,
                                                        R.s(line, "material_code"),
                                                        R.l(oc, "own_generated"),
                                                        R.l(oc, "gen_count"),
                                                        R.l(oc, "alloc_count"),
                                                        now);
                                                mapper.deleteLineCount(bill, old);
                                            }
                                            if (mapper.lineRuleExists(bill, old)
                                                    && !mapper.lineRuleExists(bill, targetSeq)) {
                                                mapper.moveLineRule(bill, old, targetSeq);
                                            }
                                            audit.record(
                                                    cu,
                                                    AuditEntryDTO.of(AuditAction.ORDER_ALERT_HANDLE)
                                                            .bill(bill)
                                                            .pi(R.s(line, "pi"))
                                                            .factory(R.s(line, "org_code"))
                                                            .count(items.size())
                                                            .why(note)
                                                            .info(
                                                                    "alert="
                                                                            + alertId
                                                                            + " type=MATERIAL action=MAP seq "
                                                                            + old
                                                                            + " -> "
                                                                            + targetSeq
                                                                            + " material="
                                                                            + R.s(
                                                                                    line,
                                                                                    "material_code")));
                                            changes.closeMaterialAlert(cu, alertId, note);
                                            changes.refreshItems(List.of(bill), false);
                                            TreeMap<String, List<Long>> held = new TreeMap<>();
                                            Map<String, String[]> hk = new HashMap<>();
                                            for (Map<String, Object> x : items) {
                                                if (reassign.contains(R.l(x, "id"))) {
                                                    String k =
                                                            R.s(x, "factory_code")
                                                                    + "\u0000"
                                                                    + R.s(x, "pi_no");
                                                    hk.put(
                                                            k,
                                                            new String[] {
                                                                R.s(x, "factory_code"),
                                                                R.s(x, "pi_no")
                                                            });
                                                    held.computeIfAbsent(k, z -> new ArrayList<>())
                                                            .add(R.l(x, "id"));
                                                }
                                            }
                                            for (Map.Entry<String, List<Long>> e :
                                                    held.entrySet()) {
                                                String[] k = hk.get(e.getKey());
                                                delivery.buildDeliveryNow(
                                                        cu,
                                                        k[0],
                                                        k[1],
                                                        e.getValue(),
                                                        null,
                                                        null,
                                                        null);
                                            }
                                            return ps;
                                        }),
                        R.s(line, "pi"));
        changes.kickResumed();
        List<List<String>> sorted = new ArrayList<>(pushes);
        sorted.sort(
                Comparator.comparing((List<String> x) -> x.get(0)).thenComparing(x -> x.get(1)));
        for (List<String> p : sorted) {
            delivery.startTask(cu, p.get(0), p.get(1));
        }
    }

    // ---------------------------------------------------------------- 查询（F-17）
    private static String direction(CurrentUser cu, String fromF) {
        if (!cu.bound()) {
            return "";
        }
        return cu.factories().contains(fromF) ? "OUT" : "IN";
    }

    @Override
    public PageVO<Map<String, Object>> listOrg(
            String factory, String pi, String bill, PageReq page) {
        CurrentUser cu = cu();
        List<String> scope = cu.readFactories(factory);
        String p = Util.blank(pi) ? null : pi.strip();
        String b = Util.blank(bill) ? null : bill.strip();
        long total = mapper.countOrg(p, b, scope);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> t : mapper.pageOrg(p, b, scope, page.offset(), page.size())) {
            items.add(
                    R.of(
                            "transfer",
                            jrow(t, ORG_FIELDS),
                            "direction",
                            direction(cu, R.s(t, "from_factory"))));
        }
        return PageVO.of(items, total, page.page(), page.size());
    }

    @Override
    public PageVO<Map<String, Object>> listBill(
            String factory, String pi, String bill, PageReq page) {
        CurrentUser cu = cu();
        List<String> scope = cu.readFactories(factory);
        String p = Util.blank(pi) ? null : pi.strip();
        String b = Util.blank(bill) ? null : bill.strip();
        long total = mapper.countBill(p, b, scope);
        List<Map<String, Object>> items =
                mapper.pageBill(p, b, scope, page.offset(), page.size()).stream()
                        .map(t -> jrow(t, BILL_FIELDS))
                        .toList();
        return PageVO.of(items, total, page.page(), page.size());
    }

    @Override
    public PageVO<Map<String, Object>> listAll(
            String kindIn, String factory, String pi, String bill, PageReq page) {
        CurrentUser cu = cu();
        String kind = Util.trim(kindIn).toUpperCase();
        boolean org = kind.isEmpty() || KIND_ORG.equals(kind);
        boolean billKind = kind.isEmpty() || KIND_BILL.equals(kind);
        if (!org && !billKind) {
            return PageVO.of(List.of(), 0, page.page(), page.size());
        }
        List<String> scope = cu.readFactories(factory);
        String p = Util.blank(pi) ? null : pi.strip();
        String b = Util.blank(bill) ? null : bill.strip();
        long total = mapper.countAll(org, billKind, p, b, scope);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> t :
                mapper.pageAll(org, billKind, p, b, scope, page.offset(), page.size())) {
            Map<String, Object> x = jrow(t, ALL_FIELDS);
            x.put(
                    "direction",
                    KIND_ORG.equals(R.s(t, "kind")) ? direction(cu, R.s(t, "from_factory")) : "");
            items.add(x);
        }
        return PageVO.of(items, total, page.page(), page.size());
    }

    private Map<String, Object> pushInfo(String kind, long tid, String docNo) {
        List<BatchVO> out = mapper.outBatches(docNo).stream().map(BatchVO::of).toList();
        List<Map<String, Object>> deliveries = new ArrayList<>();
        for (Map<String, Object> r : mapper.pushTargets(kind, tid, docNo)) {
            String org = R.s(r, "factory_code");
            if (org == null) {
                continue;
            }
            List<String> batches = new ArrayList<>();
            for (String x : R.sz(r, "batches").split(",")) {
                if (!x.isEmpty()) {
                    batches.add(x);
                }
            }
            deliveries.add(
                    R.of(
                            "factoryCode",
                            org,
                            "piNo",
                            R.s(r, "pi_no"),
                            "qty",
                            R.l(r, "n"),
                            "toPush",
                            R.l(r, "pending"),
                            "batches",
                            batches,
                            "remaining",
                            factories.isMes(org)
                                    ? delivery.remainingPushes(org, R.s(r, "pi_no"))
                                    : null));
        }
        return R.of("outBatches", out, "deliveries", deliveries);
    }

    private Map<String, Object> orgView(CurrentUser cu, long tid) {
        Map<String, Object> t = mapper.orgTransfer(tid);
        if (t == null) {
            throw Biz.err(SnErrorCode.TR_NOT_FOUND);
        }
        cu.checkRead(R.s(t, "from_factory"), R.s(t, "to_factory"));
        Map<String, Object> out = jrow(t, ORG_FIELDS);
        out.put("direction", direction(cu, R.s(t, "from_factory")));
        if (APPROVED.equals(R.s(t, "status"))) {
            out.putAll(pushInfo(KIND_ORG, tid, R.s(t, "transfer_no")));
        }
        return out;
    }

    private Map<String, Object> billTransferView(CurrentUser cu, long tid) {
        Map<String, Object> t = mapper.billTransfer(tid);
        if (t == null) {
            throw Biz.err(SnErrorCode.TR_NOT_FOUND);
        }
        if (cu.bound()) {
            Map<String, Object> r = mapper.billTransferOrgs(tid);
            List<String> orgs = new ArrayList<>();
            for (String k : List.of("f", "t")) {
                for (String x : R.sz(r, k).split(",")) {
                    if (!x.isEmpty()) {
                        orgs.add(x);
                    }
                }
            }
            orgs.add(R.s(t, "to_factory"));
            orgs.addAll(orders.billOrgs(List.of(R.s(t, "from_bill"))));
            cu.checkRead(orgs.toArray(String[]::new));
        }
        Map<String, Object> out = jrow(t, BILL_FIELDS);
        out.putAll(pushInfo(KIND_BILL, tid, R.s(t, "transfer_no")));
        return out;
    }

    @Override
    public Map<String, Object> orgView(long id) {
        return orgView(cu(), id);
    }

    @Override
    public Map<String, Object> billView(long id) {
        return billTransferView(cu(), id);
    }

    private String[] serialOf(Map<String, Object> r, Map<Long, Map<String, Object>> imps) {
        if (!R.sz(r, "seq_pi_no").isEmpty()) {
            return new String[] {
                rules.seqText(R.lo(r, "rule_version_id"), R.lo(r, "seq_dec")),
                String.valueOf(R.l(r, "seq_dec")),
                R.s(r, "seq_pi_no")
            };
        }
        Map<String, Object> d = imps.get(R.l(r, "seq_dec"));
        return d == null
                ? new String[] {"", "", ""}
                : new String[] {
                    R.s(d, "serial_text"), String.valueOf(R.l(d, "seq_dec")), R.s(d, "pi_no")
                };
    }

    private Map<Long, Map<String, Object>> importSerials(List<Map<String, Object>> rows) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (R.sz(r, "seq_pi_no").isEmpty()) {
                ids.add(R.l(r, "seq_dec"));
            }
        }
        return ids.isEmpty() ? Map.of() : imports.details(ids);
    }

    @Override
    public PageVO<Map<String, Object>> items(String kind, long tid, PageReq page) {
        CurrentUser cu = cu();
        if (KIND_ORG.equals(kind)) {
            orgView(cu, tid);
        } else {
            billTransferView(cu, tid);
        }
        long total = mapper.countItems(kind, tid);
        List<Map<String, Object>> rows = mapper.pageItems(kind, tid, page.offset(), page.size());
        Map<Long, Map<String, Object>> imps = importSerials(rows);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String cur = R.s(r, "cur_status");
            if (cu.bound()
                    && cu.factories().contains(R.s(r, "from_factory"))
                    && !cu.canRead(R.s(r, "cur_factory"))) {
                cur = "TRANSFERRED_OUT";
            }
            Map<String, Object> v = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : r.entrySet()) {
                if (!"rule_version_id".equals(e.getKey())) {
                    v.put(Camel.key(e.getKey()), e.getValue());
                }
            }
            if (!R.sz(r, "seq_pi_no").isEmpty()) {
                v.put("seqText", rules.seqText(R.lo(r, "rule_version_id"), R.lo(r, "seq_dec")));
            } else {
                String[] s = serialOf(r, imps);
                v.put("seqText", s[0]);
                v.put("seqDec", s[1]);
                v.put("seqPiNo", s[2]);
            }
            v.put("currentStatus", cur);
            out.add(v);
        }
        return PageVO.of(out, total, page.page(), page.size());
    }

    @Override
    public ExportFileVO outList(long tid) {
        CurrentUser cu = cu();
        Map<String, Object> t = orgView(cu, tid);
        String no = String.valueOf(t.get("transferNo"));
        return new ExportFileVO(
                "out_" + no,
                os -> {
                    try (Xlsx.Writer w = new Xlsx.Writer(no, OUT_LIST_HEADERS)) {
                        Map<String, String> names = factories.names();
                        long last = 0;
                        long n = 0;
                        while (true) {
                            List<Map<String, Object>> page = mapper.outListChunk(tid, last);
                            if (page.isEmpty()) {
                                break;
                            }
                            Map<Long, Map<String, Object>> imps = importSerials(page);
                            for (Map<String, Object> r : page) {
                                n++;
                                last = R.l(r, "item_id");
                                String[] s = serialOf(r, imps);
                                String from = R.s(r, "from_factory");
                                String to = R.s(r, "to_factory");
                                w.add(
                                        Arrays.asList(
                                                n,
                                                R.s(r, "sn"),
                                                s[0],
                                                s[1],
                                                s[2],
                                                from == null ? "" : from,
                                                from == null ? "" : names.getOrDefault(from, ""),
                                                R.s(r, "from_pi"),
                                                R.s(r, "from_bill"),
                                                R.sz(r, "attach_no"),
                                                R.s(r, "from_material"),
                                                R.lo(r, "from_seq"),
                                                R.sz(r, "length_text"),
                                                R.s(r, "from_delivery_batch"),
                                                R.s(r, "out_batch_no"),
                                                to,
                                                to == null ? "" : names.getOrDefault(to, "")));
                            }
                        }
                        w.write(os);
                    }
                });
    }
}
