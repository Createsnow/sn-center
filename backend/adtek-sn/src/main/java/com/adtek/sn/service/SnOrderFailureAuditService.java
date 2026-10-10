package com.adtek.sn.service;

import com.adtek.common.core.BusinessException;

/** 人工订单操作的业务拒绝痕迹；内部异常仍由平台日志和同步终态保留。 */
public interface SnOrderFailureAuditService {
    void record(long actorId, boolean schedule, String billNo, BusinessException error);
}
