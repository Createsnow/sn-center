package com.adtek.sn.service;

import com.adtek.sn.support.PageReq;
import com.adtek.sn.vo.BatchVO;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.PushRemainingVO;

import java.util.Map;

/** 交付批次页面用例（F-11、F-18）：系统管理员、普通操作员（授权组织）可查询、续推、人工确认、作废、处理告警。 */
public interface DeliveryService {
    PageVO<BatchVO> listBatches(
            String factoryCode,
            String pi,
            String batchNo,
            String source,
            String batchType,
            String result,
            String transferNo,
            boolean asc,
            PageReq page);

    BatchVO batchDetail(String batchNo);

    PageVO<Map<String, Object>> batchItems(String batchNo, PageReq page);

    ExportFileVO exportBatch(String batchNo);

    BatchVO manualConfirm(String batchNo, String reason, String basis);

    BatchVO voidBatch(String batchNo, String reason, String basis);

    PushRemainingVO resume(String factoryCode, String key);

    PushRemainingVO remaining(String factoryCode, String key);

    PageVO<Map<String, Object>> listAlerts(String status, PageReq page);

    Map<String, Object> handleAlert(long id, String note);
}
