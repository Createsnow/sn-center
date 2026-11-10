package com.adtek.sn.service;

import com.adtek.sn.query.SnItemQuery;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.vo.ExportFileVO;

import java.util.Map;

/** SN 查询（F-18，只读）。 */
public interface QueryService {
    Map<String, Object> list(SnItemQuery query, PageReq page);

    Map<String, Object> exportCheck(SnItemQuery query);

    ExportFileVO export(SnItemQuery query);
}
