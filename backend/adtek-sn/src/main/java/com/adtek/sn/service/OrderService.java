package com.adtek.sn.service;

import com.adtek.sn.dto.LineRefDTO;
import com.adtek.sn.vo.DepartmentVO;
import com.adtek.sn.vo.OrderAlertVO;
import com.adtek.sn.vo.OrderBillVO;
import com.adtek.sn.vo.OrderLineVO;
import com.adtek.sn.vo.OrderMetaVO;
import com.adtek.sn.vo.OrderTargetVO;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.RevokeConfigVO;
import com.adtek.sn.vo.SyncProgressVO;
import com.adtek.sn.vo.SyncResultVO;

import java.util.List;
import java.util.Map;

/** 生产订单快照（F-05、F-07）与订单变更提醒（F-06）：系统管理员、普通操作员（只看授权组织的单据）；全量重建只有系统管理员。 */
public interface OrderService {
    PageVO<OrderBillVO> list(
            String q,
            String customer,
            String pi,
            String factoryCode,
            Integer page,
            Integer pageSize);

    OrderMetaVO meta();

    SyncProgressVO syncProgress();

    List<String> customers(String q);

    List<DepartmentVO> departments(String factoryCode);

    List<OrderTargetVO> targets(String q, String pi, Integer limit);

    OrderBillVO billLines(String billNo);

    SyncResultVO syncBill(String billNo);

    SyncResultVO compare();

    SyncResultVO pageSync();

    SyncResultVO rebuild();

    OrderLineVO fillAttach(String billNo, Integer seq, String value);

    OrderLineVO fillLength(String billNo, Integer seq, String value);

    OrderLineVO fillUnit(String billNo, Integer seq, String value);

    PageVO<OrderAlertVO> alerts(
            String status, String billNo, String alertType, Integer page, Integer pageSize);

    Map<String, Object> handleAlert(long id, String action, String note, Integer targetSeq);

    RevokeConfigVO revokeConfig(String piNo, List<LineRefDTO> lines);
}
