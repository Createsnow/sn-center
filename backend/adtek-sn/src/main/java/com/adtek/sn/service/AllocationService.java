package com.adtek.sn.service;

import com.adtek.sn.dto.AllocateDTO;
import com.adtek.sn.support.PageReq;

import java.util.Map;

/** SN 分配（F-11 分配）：系统管理员、普通操作员（授权组织）。 */
public interface AllocationService {
    Map<String, Object> bills(String q, String tab, PageReq page);

    Map<String, Object> context(String pi);

    Map<String, Object> preview(AllocateDTO body);

    Map<String, Object> allocate(AllocateDTO body);
}
