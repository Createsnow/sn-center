package com.adtek.sn.service;

import com.adtek.sn.query.AuditQuery;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;

import java.util.Map;

/** 操作痕迹（F-21，审计报表）：系统管理员、审计员（授权组织）。 */
public interface AuditQueryService {
    PageVO<Map<String, Object>> list(AuditQuery query, PageReq page);

    Map<String, Object> exportCheck(AuditQuery query);

    ExportFileVO export(AuditQuery query);

    Map<String, Object> meta();
}
