package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.CurrentActor;
import com.adtek.sn.dto.FactoryCreateDTO;
import com.adtek.sn.service.SnFactoryFailureAuditService;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 事务拦截器之外记录拒绝，覆盖建档校验/冲突及K3业务错误；不重复通用成功审计。 */
@Aspect
@Component
@Order(50)
public class SnFactoryFailureAuditAspect {
    private static final Logger LOG = LoggerFactory.getLogger(SnFactoryFailureAuditAspect.class);
    private final SnFactoryFailureAuditService audit;
    private final CurrentActor actors;

    public SnFactoryFailureAuditAspect(SnFactoryFailureAuditService audit, CurrentActor actors) {
        this.audit = audit;
        this.actors = actors;
    }

    @Around(
            "execution(public * com.adtek.sn.service.FactoryService.create(..)) || execution(public * com.adtek.sn.service.FactoryService.synchronize(..))")
    public Object record(ProceedingJoinPoint call) throws Throwable {
        try {
            return call.proceed();
        } catch (BusinessException error) {
            try {
                boolean sync = call.getSignature().getName().equals("synchronize");
                String code = sync ? null : ((FactoryCreateDTO) call.getArgs()[0]).code();
                audit.record(actors.requireAuthenticated().userId(), sync, code, error);
            } catch (RuntimeException auditError) {
                LOG.warn(
                        "SN factory rejection audit unavailable exceptionType={}",
                        auditError.getClass().getSimpleName());
            }
            throw error;
        }
    }
}
