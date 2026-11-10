package com.adtek.sn.service;

import com.adtek.sn.dto.TransferDTO;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;

import java.util.Map;

/** 转单（F-15）、转组织（F-16）与记录页（F-17）：系统管理员、普通操作员直接转移，原因必填。 */
public interface TransferService {
    PageVO<Map<String, Object>> listAll(
            String kind, String factory, String pi, String bill, PageReq page);

    Map<String, Object> previewAuto(TransferDTO body);

    Map<String, Object> transfer(TransferDTO body);

    Map<String, Object> locate(String q);

    Map<String, Object> sources(String kind, String factory, String pi);

    Map<String, Object> preview(String kind, TransferDTO body);

    Map<String, Object> transferBill(TransferDTO body);

    PageVO<Map<String, Object>> listBill(String factory, String pi, String bill, PageReq page);

    Map<String, Object> billView(long id);

    PageVO<Map<String, Object>> items(String kind, long id, PageReq page);

    Map<String, Object> direct(TransferDTO body);

    PageVO<Map<String, Object>> listOrg(String factory, String pi, String bill, PageReq page);

    Map<String, Object> orgView(long id);

    ExportFileVO outList(long id);
}
