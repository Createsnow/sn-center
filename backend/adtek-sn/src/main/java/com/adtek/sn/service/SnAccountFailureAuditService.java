package com.adtek.sn.service;

import com.adtek.common.core.BusinessException;

/** 账户用例拒绝后的独立痕迹，不接收请求体或密码。 */
public interface SnAccountFailureAuditService {
    void record(
            long actorId, String action, String path, String factoryId, BusinessException error);
}
