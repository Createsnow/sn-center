package com.adtek.sn.vo;

import com.adtek.sn.support.R;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 交付批次（含转出批次）。 */
public class BatchVO {
    public Long id;
    public String batchNo;
    public String source;
    public String batchType;
    public String factoryCode;
    public String piNo;
    public Long qty;
    public String itemsDigest;
    public String result;
    public String confirmMethod;
    public LocalDateTime confirmedAt;
    public String failCode;
    public String failReason;
    public Boolean voidable;
    public Long acceptedQty;
    public String pushState;
    public Long retryCount;
    public Long receiptTimeouts;
    public LocalDateTime nextRetryAt;
    public LocalDateTime receivedAt;
    public Long pushCount;
    public Long repushCount;
    public LocalDateTime lastPushAt;
    public LocalDateTime pulledAt;
    public String transferKind;
    public String transferDocNo;
    public String rebuiltFrom;
    public String rebuiltTo;
    public String manualReason;
    public String manualBasis;
    public String manualBy;
    public LocalDateTime manualAt;
    public String createdBy;
    public LocalDateTime createdAt;

    /** 作废时当场重建的批次号（只在作废应答中有值）。 */
    public List<String> rebuilt;

    /** 详情：回执存档、号段、单据、物料（只在详情中有值）。 */
    public List<Map<String, Object>> receipts;

    public List<Map<String, Object>> segments;
    public List<String> bills;
    public List<String> materials;

    public static BatchVO of(Map<String, Object> b) {
        if (b == null) {
            return null;
        }
        BatchVO v = new BatchVO();
        v.id = R.lo(b, "id");
        v.batchNo = R.s(b, "batch_no");
        v.source = R.s(b, "source");
        v.batchType = R.s(b, "batch_type");
        v.factoryCode = R.s(b, "factory_code");
        v.piNo = R.s(b, "pi_no");
        v.qty = R.lo(b, "qty");
        v.itemsDigest = R.s(b, "items_digest");
        v.result = R.s(b, "result");
        v.confirmMethod = R.s(b, "confirm_method");
        v.confirmedAt = R.t(b, "confirmed_at");
        v.failCode = R.s(b, "fail_code");
        v.failReason = R.s(b, "fail_reason");
        v.voidable = R.bo(b, "voidable");
        v.acceptedQty = R.lo(b, "accepted_qty");
        v.pushState = R.s(b, "push_state");
        v.retryCount = R.lo(b, "retry_count");
        v.receiptTimeouts = R.lo(b, "receipt_timeouts");
        v.nextRetryAt = R.t(b, "next_retry_at");
        v.receivedAt = R.t(b, "received_at");
        v.pushCount = R.lo(b, "push_count");
        v.repushCount = R.lo(b, "repush_count");
        v.lastPushAt = R.t(b, "last_push_at");
        v.pulledAt = R.t(b, "pulled_at");
        v.transferKind = R.s(b, "transfer_kind");
        v.transferDocNo = R.s(b, "transfer_doc_no");
        v.rebuiltFrom = R.s(b, "rebuilt_from");
        v.rebuiltTo = R.s(b, "rebuilt_to");
        v.manualReason = R.s(b, "manual_reason");
        v.manualBasis = R.s(b, "manual_basis");
        v.manualBy = R.s(b, "manual_by");
        v.manualAt = R.t(b, "manual_at");
        v.createdBy = R.s(b, "created_by");
        v.createdAt = R.t(b, "created_at");
        return v;
    }
}
