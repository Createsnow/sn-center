package com.adtek.sn.service;

import com.adtek.sn.support.PageReq;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;

import java.util.List;
import java.util.Map;

/** SN 导出（F-14）：已分配的号按「生产组织 + PI + 单据」查看与下载；只读。 */
public interface SnExportService {
    PageVO<Map<String, Object>> bills(
            String factory, String q, Boolean mes, String pi, PageReq page);

    List<Map<String, Object>> lines(String factory, String pi, String bill);

    PageVO<Map<String, Object>> segments(String factory, String pi, String bill, PageReq page);

    PageVO<Map<String, Object>> items(
            String factory,
            String pi,
            String bill,
            PageReq page,
            Integer billSeq,
            String material,
            String q,
            String snFrom,
            String snTo);

    ExportFileVO file(
            String factory, String pi, String bill, String material, String snFrom, String snTo);
}
