package com.adtek.sn.service.impl;

import com.adtek.sn.support.CurrentUser;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 占位：交付批次（移植中）。 */
@Service
public class DeliveryServiceImpl {
    /** 作废结果：当场重建的批次与涉及的单据。 */
    public record VoidResult(List<Map<String, Object>> newBatches, Set<String> bills) {}

    public void kickBill(String billNo) {}

    public Map<String, Object> mustBatch(String batchNo, boolean lock) {
        return null;
    }

    public VoidResult voidInTx(CurrentUser cu, Map<String, Object> batch, String reason, String ref) {
        return null;
    }

    public void afterVoid(CurrentUser cu, Map<String, Object> batch, Set<String> bills) {}
}
