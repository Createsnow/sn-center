package com.adtek.sn.service;

import com.adtek.common.core.BusinessException;

/** 工厂用例拒绝后的独立事实；只接收受控错误与工厂编码，不接收请求正文。 */
public interface SnFactoryFailureAuditService {
    void record(long actorId, boolean synchronize, String code, BusinessException error);
}
