package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.CurrentActor;
import com.adtek.sn.dto.OrderSyncDTO;
import com.adtek.sn.service.SnOrderFailureAuditService;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 人工用例事务之外保留一次业务拒绝；调度失败由其原运行记录固定SYSTEM事实。 */
@Aspect
@Component
@Order(50)
public class SnOrderFailureAuditAspect {
    private static final Logger LOG = LoggerFactory.getLogger(SnOrderFailureAuditAspect.class);
    private final SnOrderFailureAuditService audit;
    private final CurrentActor actors;

    public SnOrderFailureAuditAspect(SnOrderFailureAuditService audit, CurrentActor actors) {
        this.audit = audit;
        this.actors = actors;
    }

    @Around(
            "execution(public * com.adtek.sn.service.OrderService.synchronize(..)) || execution(public * com.adtek.sn.service.OrderScheduleService.save(..))")
    public Object record(ProceedingJoinPoint call) throws Throwable {
        try {
            return call.proceed();
        } catch (BusinessException error) {
            // 结构校验对应原型请求校验，不属于应留痕的业务拒绝。
            if (error.getError().code()
                    == com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT.code())
                throw error;
            try {
                boolean schedule = call.getSignature().getName().equals("save");
                String bill = schedule ? null : ((OrderSyncDTO) call.getArgs()[0]).billNo();
                audit.record(actors.requireAuthenticated().userId(), schedule, bill, error);
            } catch (RuntimeException unavailable) {
                LOG.warn(
                        "SN order rejection audit unavailable exceptionType={}",
                        unavailable.getClass().getSimpleName());
            }
            throw error;
        }
    }
}
