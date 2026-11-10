package com.adtek.sn.service.impl;

import static com.adtek.sn.service.impl.BatchServiceImpl.M_MANUAL;
import static com.adtek.sn.service.impl.BatchServiceImpl.M_RECEIPT;
import static com.adtek.sn.service.impl.BatchServiceImpl.RECEIPT_NG;
import static com.adtek.sn.service.impl.BatchServiceImpl.RECEIPT_OK;
import static com.adtek.sn.service.impl.BatchServiceImpl.SRC_PULL;
import static com.adtek.sn.service.impl.BatchServiceImpl.SUCCESS;
import static com.adtek.sn.service.impl.BatchServiceImpl.TRANSFER_OUT;
import static com.adtek.sn.service.impl.BatchServiceImpl.VOIDED;
import static com.adtek.sn.service.impl.BatchServiceImpl.XO_KEY;

import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.mapper.DeliveryMapper;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.MesWire;
import com.adtek.sn.support.R;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 回执（原系统 delivery/receipts.py）：核对、存档、确认或记失败。MES 回执（接口 ③）、人工确认都在这里落到批次和号上；先到的回执生效，后到的只存档。 */
@Service
public class ReceiptServiceImpl {
    /** 回执处理结果。verdict：CONFIRMED / REJECTED / MISMATCH / ARCHIVED / VOIDED。 */
    public record Applied(
            String verdict, Map<String, Object> batch, List<Map<String, Object>> details) {}

    private final DeliveryMapper mapper;
    private final BatchServiceImpl batches;
    private final BillStatsServiceImpl billStats;
    private final AuditServiceImpl audit;
    private final PushServiceImpl push;
    private final Tx tx;

    public ReceiptServiceImpl(
            DeliveryMapper mapper,
            BatchServiceImpl batches,
            BillStatsServiceImpl billStats,
            AuditServiceImpl audit,
            @Lazy PushServiceImpl push,
            Tx tx) {
        this.mapper = mapper;
        this.batches = batches;
        this.billStats = billStats;
        this.audit = audit;
        this.push = push;
        this.tx = tx;
    }

    private static List<Map<String, Object>> verify(Map<String, Object> b, Map<String, Object> r) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        Object no = r.get("batchNo");
        if (!R.s(b, "batch_no").equals(no)) {
            diffs.add(diff("batchNo", "期望 " + R.s(b, "batch_no") + "，收到 " + pyStr(no)));
        }
        Object code = r.get("code");
        if (!RECEIPT_OK.equals(code) && !RECEIPT_NG.equals(code)) {
            diffs.add(diff("code", "只能是 OK / NG，收到 " + (code instanceof String s ? s : "(空)")));
        }
        return diffs;
    }

    /** Python str() 的文本（None → None，布尔 → True / False）。 */
    static String pyStr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean bv) {
            return bv ? "True" : "False";
        }
        return v.toString();
    }

    static Map<String, Object> diff(String field, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("reason", reason);
        return m;
    }

    private void archive(
            Map<String, Object> b,
            String method,
            String outcome,
            String verdict,
            String detail,
            Map<String, Object> r,
            String by) {
        String o = Util.cut(outcome, 16);
        mapper.insertReceipt(
                R.l(b, "id"),
                method,
                o == null ? "" : o,
                verdict,
                Util.cut(detail, 1000),
                MesWire.gzip(MesWire.canonical(r)),
                by,
                Util.now());
    }

    /** 处理一份 MES 回执（接口 ③）：在事务里提交；回执生效（确认或整批退回）后唤醒正在等这一批的推送任务。 */
    public Applied applyReceipt(CurrentUser cu, String batchNo, Map<String, Object> r) {
        String method = M_RECEIPT;
        Applied applied =
                tx.run(
                        () -> {
                            Map<String, Object> b = batches.mustBatch(batchNo, true);
                            String code = r.get("code") instanceof String s ? s : "";
                            if (VOIDED.equals(R.s(b, "result"))) {
                                archive(b, method, code, "VOIDED", "批次已作废", r, cu.empNo());
                                return new Applied("VOIDED", b, List.of());
                            }
                            if (SUCCESS.equals(R.s(b, "result"))) {
                                archive(b, method, code, "ARCHIVED", "批次早已确认，只存档", r, cu.empNo());
                                return new Applied("ARCHIVED", b, List.of());
                            }
                            LocalDateTime now = Util.now();
                            List<Map<String, Object>> diffs = verify(b, r);
                            if (!diffs.isEmpty()) {
                                String text =
                                        diffs.stream()
                                                .map(d -> d.get("field") + ": " + d.get("reason"))
                                                .collect(Collectors.joining("；"));
                                markFailed(b, "RECEIPT_MISMATCH", "回执与批次不符：" + text, false, now);
                                archive(b, method, code, "MISMATCH", text, r, cu.empNo());
                                audit.record(
                                        cu,
                                        AuditEntryDTO.of(AuditAction.SN_RECEIPT)
                                                .fail("回执与批次不符：" + text)
                                                .factory(R.s(b, "factory_code"))
                                                .pi(R.s(b, "pi_no"))
                                                .count(R.l(b, "qty"))
                                                .batch(R.s(b, "batch_no"))
                                                .info("method=" + method));
                                return new Applied(
                                        "MISMATCH", batches.mustBatch(batchNo, false), diffs);
                            }
                            if (RECEIPT_NG.equals(code)) {
                                String reason =
                                        r.get("message") instanceof String m && !m.strip().isEmpty()
                                                ? m.strip()
                                                : "MES 整批退回";
                                markFailed(b, "REJECTED", reason, true, now);
                                archive(b, method, code, "REJECTED", reason, r, cu.empNo());
                                audit.record(
                                        cu,
                                        AuditEntryDTO.of(AuditAction.SN_RECEIPT)
                                                .factory(R.s(b, "factory_code"))
                                                .pi(R.s(b, "pi_no"))
                                                .count(R.l(b, "qty"))
                                                .batch(R.s(b, "batch_no"))
                                                .why(reason)
                                                .info("method=" + method + " code=NG"));
                                return new Applied(
                                        "REJECTED", batches.mustBatch(batchNo, false), List.of());
                            }
                            confirm(cu, b, method, true, now);
                            archive(b, method, code, "CONFIRMED", null, r, cu.empNo());
                            Map<String, Object> b2 = batches.mustBatch(batchNo, false);
                            audit.record(
                                    cu,
                                    AuditEntryDTO.of(AuditAction.SN_RECEIPT)
                                            .factory(R.s(b, "factory_code"))
                                            .pi(R.s(b, "pi_no"))
                                            .count(R.l(b, "qty"))
                                            .batch(R.s(b, "batch_no"))
                                            .status(
                                                    Util.TO_PUSH,
                                                    TRANSFER_OUT.equals(R.s(b, "batch_type"))
                                                            ? null
                                                            : Util.PUSHED)
                                            .info(
                                                    "method="
                                                            + method
                                                            + " result="
                                                            + R.s(b2, "result")
                                                            + " accepted="
                                                            + R.s(b2, "accepted_qty")));
                            return new Applied("CONFIRMED", b2, List.of());
                        });
        if (!"VOIDED".equals(applied.verdict()) && !"ARCHIVED".equals(applied.verdict())) {
            wakeTask(applied.batch(), "CONFIRMED".equals(applied.verdict()));
        }
        return applied;
    }

    public void markFailed(
            Map<String, Object> b,
            String code,
            String reason,
            boolean voidable,
            LocalDateTime now) {
        mapper.markFailed(R.l(b, "id"), code, Util.cut(reason, 1000), voidable, now);
    }

    /** 批次确认：交付批次的号改为已推送并记 MES 持有组织；转出批次只在持有组织仍是来源组织时清除。 */
    public void confirm(
            CurrentUser cu,
            Map<String, Object> b,
            String method,
            boolean byMes,
            LocalDateTime now) {
        long accepted = 0;
        long id = R.l(b, "id");
        if (byMes) {
            mapper.acceptItems(id);
            accepted = R.l(b, "qty");
        }
        mapper.confirmBatch(id, method, accepted, now);
        if (!TRANSFER_OUT.equals(R.s(b, "batch_type"))) {
            boolean pulled;
            if (M_MANUAL.equals(method)) {
                pulled = SRC_PULL.equals(R.s(b, "source"));
            } else {
                pulled =
                        b.get("received_at") == null
                                && (SRC_PULL.equals(R.s(b, "source"))
                                        || b.get("pulled_at") != null);
            }
            String mode = pulled ? Util.MODE_PULL : Util.MODE_PUSH;
            mapper.itemsPushed(R.s(b, "batch_no"), R.s(b, "factory_code"), mode, now);
            billStats.touchBatch(R.s(b, "batch_no"));
        } else {
            mapper.clearHolder(id, R.s(b, "factory_code"), now);
            billStats.touchBatchItems(id);
        }
    }

    /** 批次确认、整批退回或作废后：正在等这一批的推送任务马上再跑一次；resume 时已停止的任务也接着推。 */
    public void wakeTask(Map<String, Object> b, boolean resume) {
        String key = TRANSFER_OUT.equals(R.s(b, "batch_type")) ? XO_KEY : R.s(b, "pi_no");
        mapper.wakeTask(R.s(b, "factory_code"), key, R.s(b, "batch_no"), resume, Util.now());
        push.poke();
    }
}
