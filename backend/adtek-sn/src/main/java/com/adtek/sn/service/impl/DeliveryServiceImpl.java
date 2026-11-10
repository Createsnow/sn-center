package com.adtek.sn.service.impl;

import static com.adtek.sn.service.impl.BatchServiceImpl.FAILED;
import static com.adtek.sn.service.impl.BatchServiceImpl.M_MANUAL;
import static com.adtek.sn.service.impl.BatchServiceImpl.NEW;
import static com.adtek.sn.service.impl.BatchServiceImpl.TRANSFER_OUT;
import static com.adtek.sn.service.impl.BatchServiceImpl.UNCONFIRMED;
import static com.adtek.sn.service.impl.BatchServiceImpl.XO_KEY;

import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.mapper.DeliveryMapper;
import com.adtek.sn.service.DeliveryService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.Camel;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.support.Xlsx;
import com.adtek.sn.vo.BatchVO;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.PushRemainingVO;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 交付批次（原系统 delivery 包）：页面用例（批次列表、详情、明细、导出、人工确认、作废、继续推送、交付告警）与供其他用例调用的门面 （建批、作废、推送启动、转出冻结）。 */
@Service
public class DeliveryServiceImpl implements DeliveryService {
    public static final List<String> EXPORT_HEADERS =
            List.of(
                    "序号",
                    "完整SN",
                    "rev",
                    "推送类型",
                    "组织编号",
                    "生产组织名称",
                    "流水归属PI",
                    "十进制流水",
                    "PI（转出为转出前）",
                    "单据编号",
                    "附件项次",
                    "物料编码",
                    "ERP行号",
                    "米数",
                    "客户编码",
                    "转入生产组织",
                    "转入生产组织名称",
                    "回执结果");

    /** 作废结果：当场重建的批次与涉及的单据。 */
    public record VoidResult(List<Map<String, Object>> newBatches, Set<String> bills) {}

    private final DeliveryMapper mapper;
    private final BatchServiceImpl batches;
    private final ReceiptServiceImpl receipts;
    private final PushServiceImpl push;
    private final FactoryServiceImpl factories;
    private final ChangeServiceImpl changes;
    private final BillStatsServiceImpl billStats;
    private final AuditServiceImpl audit;
    private final SnSettings settings;
    private final Tx tx;

    public DeliveryServiceImpl(
            DeliveryMapper mapper,
            BatchServiceImpl batches,
            ReceiptServiceImpl receipts,
            PushServiceImpl push,
            FactoryServiceImpl factories,
            @Lazy ChangeServiceImpl changes,
            BillStatsServiceImpl billStats,
            AuditServiceImpl audit,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.batches = batches;
        this.receipts = receipts;
        this.push = push;
        this.factories = factories;
        this.changes = changes;
        this.billStats = billStats;
        this.audit = audit;
        this.settings = settings;
        this.tx = tx;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo());
    }

    // ---------------------------------------------------------------- 门面
    public Map<String, Object> mustBatch(String batchNo, boolean lock) {
        return batches.mustBatch(batchNo, lock);
    }

    public void kickBill(String billNo) {
        push.kickBill(billNo);
    }

    public PushRemainingVO remainingPushes(String org, String key) {
        return push.remainingPushes(org, key);
    }

    public void startTask(CurrentUser cu, String org, String key) {
        push.startTask(cu, org, key);
    }

    public List<Map<String, Object>> buildDeliveryNow(
            CurrentUser cu,
            String org,
            String pi,
            List<Long> ids,
            String transferKind,
            String transferDocNo,
            String rebuiltFrom) {
        return batches.buildDeliveryNow(cu, org, pi, ids, transferKind, transferDocNo, rebuiltFrom);
    }

    public List<Map<String, Object>> buildTransferOut(
            CurrentUser cu, String org, List<Map<String, Object>> entries, String rebuiltFrom) {
        return batches.buildTransferOut(cu, org, entries, rebuiltFrom);
    }

    // ---------------------------------------------------------------- 人工处理（5.2.5 第 7 条）
    private static String[] checkManual(String reason, String basis) {
        String r = Util.trim(reason);
        String b = Util.trim(basis);
        if (r.isEmpty() || r.length() > 500) {
            throw Biz.err(SnErrorCode.REASON_REQUIRED);
        }
        if (b.isEmpty() || b.length() > 500) {
            throw Biz.err(SnErrorCode.BATCH_BASIS_REQUIRED);
        }
        return new String[] {r, b};
    }

    private static void checkPending(Map<String, Object> b) {
        String result = R.s(b, "result");
        if (!UNCONFIRMED.equals(result) && !FAILED.equals(result)) {
            throw Biz.err(
                    SnErrorCode.BATCH_NOT_PENDING,
                    "batch_no",
                    R.s(b, "batch_no"),
                    "result",
                    result);
        }
    }

    /** 人工确认：MES 方确认已全部入库但回执交不上来。批次成功（确认方式「人工」），推送不再重试这一批。 */
    @Override
    public BatchVO manualConfirm(String batchNo, String reason, String basis) {
        CurrentUser cu = cu().requireOperator();
        String[] rb = checkManual(reason, basis);
        Map<String, Object> b =
                tx.run(
                        () -> {
                            Map<String, Object> x = batches.mustBatch(batchNo, true);
                            cu.checkWrite(R.s(x, "factory_code"));
                            checkPending(x);
                            LocalDateTime now = Util.now();
                            receipts.confirm(cu, x, M_MANUAL, false, now);
                            mapper.setManual(R.l(x, "id"), rb[0], rb[1], cu.empNo(), now);
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.SN_BATCH_MANUAL)
                                            .factory(R.s(x, "factory_code"))
                                            .pi(R.s(x, "pi_no"))
                                            .count(R.l(x, "qty"))
                                            .batch(R.s(x, "batch_no"))
                                            .why(rb[0])
                                            .info("basis=" + rb[1] + " was=" + R.s(x, "result")));
                            return x;
                        });
        receipts.wakeTask(b, true);
        return BatchVO.of(batches.mustBatch(R.s(b, "batch_no"), false));
    }

    /** 作废：MES 没有处理的批次。交付类批次的号清除批次号：NEW 回到号池，其余类型当场重建；转出批次按原明细重建。 */
    @Override
    public BatchVO voidBatch(String batchNo, String reason, String basis) {
        CurrentUser cu = cu().requireOperator();
        String[] rb = checkManual(reason, basis);
        Object[] res =
                tx.run(
                        () -> {
                            Map<String, Object> x = batches.mustBatch(batchNo, true);
                            cu.checkWrite(R.s(x, "factory_code"));
                            checkPending(x);
                            if (FAILED.equals(R.s(x, "result")) && !R.b(x, "voidable")) {
                                throw Biz.err(
                                        SnErrorCode.BATCH_NOT_VOIDABLE,
                                        "batch_no",
                                        R.s(x, "batch_no"));
                            }
                            return new Object[] {x, voidInTx(cu, x, rb[0], rb[1])};
                        });
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) res[0];
        VoidResult vr = (VoidResult) res[1];
        afterVoid(cu, b, vr.bills());
        BatchVO out = BatchVO.of(batches.mustBatch(R.s(b, "batch_no"), false));
        out.rebuilt = vr.newBatches().stream().map(x -> R.s(x, "batch_no")).toList();
        return out;
    }

    /** 作废批次 b（已加锁、未确认或失败）的事务内部分；也用于删号时作废含被删号的批次。提交后调 {@link #afterVoid}。 */
    public VoidResult voidInTx(CurrentUser cu, Map<String, Object> b, String r, String bs) {
        List<Map<String, Object>> rebuilt = new ArrayList<>();
        LocalDateTime now = Util.now();
        long id = R.l(b, "id");
        String no = R.s(b, "batch_no");
        mapper.voidBatch(id, r, bs, cu.empNo(), now);
        Set<String> bills = new LinkedHashSet<>();
        if (!TRANSFER_OUT.equals(R.s(b, "batch_type"))) {
            bills.addAll(mapper.billsOfBatch(no));
            List<Long> ids = mapper.lockBatchIds(no);
            billStats.touchIds(ids);
            mapper.unbatch(no, now);
            changes.refreshItems(bills, false);
            if (!NEW.equals(R.s(b, "batch_type"))
                    && !ids.isEmpty()
                    && factories.isMes(R.s(b, "factory_code"))) {
                rebuilt =
                        batches.buildDeliveryNow(
                                cu,
                                R.s(b, "factory_code"),
                                R.s(b, "pi_no"),
                                ids,
                                R.s(b, "transfer_kind"),
                                R.s(b, "transfer_doc_no"),
                                no);
                if (!rebuilt.isEmpty()) {
                    mapper.setRebuiltTo(id, joinNos(rebuilt));
                }
            }
        } else {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map<String, Object> e : mapper.outEntries(id)) {
                Map<String, Object> m = new HashMap<>(e);
                m.put("transfer_kind", R.s(b, "transfer_kind"));
                m.put("transfer_doc_no", R.s(b, "transfer_doc_no"));
                entries.add(m);
            }
            rebuilt = batches.buildTransferOut(cu, R.s(b, "factory_code"), entries, no);
            mapper.setRebuiltTo(id, joinNos(rebuilt));
            mapper.moveOutBatch(no, rebuilt.isEmpty() ? null : R.s(rebuilt.get(0), "batch_no"));
        }
        audit.record(
                cu,
                AuditEntryDTO.of(AuditAction.SN_BATCH_VOID)
                        .factory(R.s(b, "factory_code"))
                        .pi(R.s(b, "pi_no"))
                        .count(R.l(b, "qty"))
                        .batch(no)
                        .why(r)
                        .info(
                                "basis="
                                        + bs
                                        + " was="
                                        + R.s(b, "result")
                                        + " type="
                                        + R.s(b, "batch_type")
                                        + (rebuilt.isEmpty()
                                                ? ""
                                                : " rebuilt="
                                                        + rebuilt.stream()
                                                                .map(x -> R.s(x, "batch_no"))
                                                                .collect(
                                                                        Collectors.joining(",")))));
        return new VoidResult(rebuilt, bills);
    }

    private static String joinNos(List<Map<String, Object>> rebuilt) {
        String s = rebuilt.stream().map(x -> R.s(x, "batch_no")).collect(Collectors.joining(","));
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** 作废提交后：推送任务正在等这一批的马上接着推。 */
    public void afterVoid(CurrentUser cu, Map<String, Object> b, Set<String> bills) {
        receipts.wakeTask(b, false);
        if (!TRANSFER_OUT.equals(R.s(b, "batch_type"))) {
            for (String no : bills) {
                push.kickBill(no);
            }
            if (R.s(b, "pi_no") != null && factories.isMes(R.s(b, "factory_code"))) {
                push.startTask(cu, R.s(b, "factory_code"), R.s(b, "pi_no"));
            }
        } else {
            push.startTask(cu, R.s(b, "factory_code"), XO_KEY);
        }
    }

    // ---------------------------------------------------------------- 推送
    @Override
    public PushRemainingVO resume(String factoryCode, String key) {
        return push.resume(cu().requireOperator(), factoryCode, key);
    }

    @Override
    public PushRemainingVO remaining(String factoryCode, String key) {
        cu().requireOperator().checkRead(factoryCode);
        PushRemainingVO v = push.remainingPushes(factoryCode, key);
        v.task = push.taskState(factoryCode, key);
        return v;
    }

    // ---------------------------------------------------------------- 交付告警（5.2.5 第 8 条）
    @Override
    public Map<String, Object> handleAlert(long alertId, String note) {
        CurrentUser cu = cu().requireOperator();
        String why = Util.trim(note);
        if (why.isEmpty() || why.length() > 500) {
            throw Biz.err(SnErrorCode.REASON_REQUIRED);
        }
        tx.exec(
                () -> {
                    Map<String, Object> a = mapper.alertLock(alertId);
                    if (a == null || !"OPEN".equals(R.s(a, "status"))) {
                        throw Biz.err(SnErrorCode.DALERT_NOT_FOUND);
                    }
                    String f = R.s(a, "factory_code");
                    cu.checkWrite(f == null || f.isEmpty() ? "-" : f);
                    mapper.closeAlert(alertId, cu.empNo(), Util.now(), why);
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.SN_ALERT_HANDLE)
                                    .factory(f)
                                    .batch(R.s(a, "batch_no"))
                                    .range(R.s(a, "sn"), R.s(a, "sn"))
                                    .why(why)
                                    .info("alert=" + alertId + " type=" + R.s(a, "alert_type")));
                });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", alertId);
        return out;
    }

    /** 号所在转出批次未确认或失败（5.2.8 第 7 条）。 */
    public Set<Long> frozenByTransferOut(List<Long> itemIds) {
        Set<Long> out = new java.util.HashSet<>();
        for (List<Long> part : R.chunks(itemIds, 1000)) {
            out.addAll(mapper.frozenByTransferOut(part));
        }
        return out;
    }

    @Override
    public PageVO<Map<String, Object>> listAlerts(String status, PageReq page) {
        CurrentUser cu = cu().requireOperator();
        String st = Util.trim(status);
        if (st.isEmpty()) {
            st = "OPEN";
        }
        String s = "ALL".equals(st) ? null : st;
        List<String> scope = cu.readFactories(null);
        long total = mapper.countAlerts(s, scope);
        List<Map<String, Object>> rows = mapper.pageAlerts(s, scope, page.offset(), page.size());
        return PageVO.of(rows.stream().map(Camel::map).toList(), total, page.page(), page.size());
    }

    // ---------------------------------------------------------------- 页面查询（F-18）
    @Override
    public PageVO<BatchVO> listBatches(
            String factoryCode,
            String pi,
            String batchNo,
            String source,
            String batchType,
            String result,
            String transferNo,
            boolean asc,
            PageReq page) {
        CurrentUser cu = cu().requireOperator();
        List<String> scope = cu.readFactories(factoryCode);
        String bt = Util.trimOrNull(batchType);
        boolean delivery = "DELIVERY".equals(bt);
        if (delivery) {
            bt = null;
        }
        String p = Util.trimOrNull(pi);
        String no = Util.trimOrNull(batchNo);
        String src = Util.trimOrNull(source);
        String res = Util.trimOrNull(result);
        String tn = Util.trimOrNull(transferNo);
        long total = mapper.countBatches(scope, delivery, p, no, src, bt, res, tn);
        List<Map<String, Object>> rows =
                mapper.pageBatches(
                        scope, delivery, p, no, src, bt, res, tn, asc, page.offset(), page.size());
        return PageVO.of(rows.stream().map(BatchVO::of).toList(), total, page.page(), page.size());
    }

    @Override
    public BatchVO batchDetail(String batchNo) {
        CurrentUser cu = cu().requireOperator();
        Map<String, Object> b = batches.mustBatch(batchNo, false);
        cu.checkRead(R.s(b, "factory_code"));
        long id = R.l(b, "id");
        BatchVO out = BatchVO.of(b);
        out.receipts = mapper.receiptsOf(id).stream().map(Camel::map).toList();
        List<Map<String, Object>> segs = new ArrayList<>();
        for (Map<String, Object> r : mapper.segmentsOf(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seqPiNo", R.s(r, "seq_pi_no"));
            m.put("startSeq", R.l(r, "s"));
            m.put("endSeq", R.l(r, "e"));
            m.put("qty", R.l(r, "n"));
            m.put("startSn", R.s(r, "first_sn"));
            m.put("endSn", R.s(r, "last_sn"));
            segs.add(m);
        }
        out.segments = segs;
        out.bills = mapper.billsOfBatchItems(id);
        out.materials = mapper.materialsOfBatchItems(id);
        return out;
    }

    @Override
    public PageVO<Map<String, Object>> batchItems(String batchNo, PageReq page) {
        CurrentUser cu = cu().requireOperator();
        Map<String, Object> b = batches.mustBatch(batchNo, false);
        cu.checkRead(R.s(b, "factory_code"));
        List<Map<String, Object>> rows =
                mapper.batchItemsPage(R.l(b, "id"), page.offset(), page.size());
        return PageVO.of(
                rows.stream().map(Camel::map).toList(), R.l(b, "qty"), page.page(), page.size());
    }

    @Override
    public ExportFileVO exportBatch(String batchNo) {
        CurrentUser cu = cu().requireOperator();
        Map<String, Object> b = batches.mustBatch(batchNo, false);
        cu.checkRead(R.s(b, "factory_code"));
        String no = R.s(b, "batch_no");
        return new ExportFileVO(
                "batch_" + no,
                out -> {
                    try (Xlsx.Writer w = new Xlsx.Writer(no, EXPORT_HEADERS)) {
                        Map<String, String> names = factories.names();
                        String org = R.s(b, "factory_code");
                        String orgName = names.getOrDefault(org, "");
                        long last = 0;
                        while (true) {
                            List<Map<String, Object>> rows = mapper.exportChunk(R.l(b, "id"), last);
                            if (rows.isEmpty()) {
                                break;
                            }
                            for (Map<String, Object> r : rows) {
                                last = R.l(r, "line_no");
                                String to = R.s(r, "to_factory");
                                w.add(
                                        Arrays.asList(
                                                R.lo(r, "line_no"),
                                                R.s(r, "sn"),
                                                R.lo(r, "rev"),
                                                R.s(r, "push_type"),
                                                org,
                                                orgName,
                                                R.s(r, "seq_pi_no"),
                                                R.lo(r, "seq_dec"),
                                                R.s(r, "pi_no"),
                                                R.s(r, "bill_no"),
                                                R.sz(r, "attach_no"),
                                                R.s(r, "material_code"),
                                                R.lo(r, "bill_seq"),
                                                R.sz(r, "length_text"),
                                                R.s(r, "customer_code"),
                                                to == null ? "" : to,
                                                to == null || to.isEmpty()
                                                        ? ""
                                                        : names.getOrDefault(to, ""),
                                                R.s(r, "receipt_result")));
                            }
                        }
                        w.write(out);
                    }
                });
    }
}
