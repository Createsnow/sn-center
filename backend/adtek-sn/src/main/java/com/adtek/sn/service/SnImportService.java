package com.adtek.sn.service;

import com.adtek.sn.support.PageReq;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;

import java.util.Map;

/** SN 导入：系统管理员、普通操作员（只能导入授权组织的单据）。 */
public interface SnImportService {
    ExportFileVO template();

    Map<String, Object> check(byte[] content);

    Map<String, Object> run(byte[] content, String fileName);

    Map<String, Object> summary(String importNo);

    Map<String, Object> delete(String importNo);

    PageVO<Map<String, Object>> items(
            String pi, String billNo, String sn, String importNo, PageReq page);
}
