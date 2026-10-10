package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.CurrentActor;
import com.adtek.sn.dto.SnAccountCreateDTO;
import com.adtek.sn.dto.SnAccountStatusDTO;
import com.adtek.sn.dto.SnAccountUpdateDTO;
import com.adtek.sn.service.SnAccountFailureAuditService;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 用例外侧补齐SN账号拒绝痕迹；只提取工厂ID和路由，不把DTO/密码交给审计服务。 */
@Aspect
@Component
public class SnAccountFailureAuditAspect {
    private static final Logger LOG = LoggerFactory.getLogger(SnAccountFailureAuditAspect.class);
    private final SnAccountFailureAuditService audit;
    private final CurrentActor actors;

    public SnAccountFailureAuditAspect(SnAccountFailureAuditService audit, CurrentActor actors) {
        this.audit = audit;
        this.actors = actors;
    }

    @Around("execution(public * com.adtek.sn.service.SnAccountService.*(..))")
    public Object record(ProceedingJoinPoint call) throws Throwable {
        try {
            return call.proceed();
        } catch (BusinessException error) {
            if (error.getError().httpStatus() >= 400 && error.getError().httpStatus() < 500) {
                try {
                    long actor = actors.requireAuthenticated().userId();
                    Object[] args = call.getArgs();
                    String method = call.getSignature().getName();
                    String id =
                            args.length > 1
                                            && args[0] instanceof String value
                                            && value.matches("[1-9][0-9]{0,18}")
                                    ? value
                                    : "{id}";
                    String action = "system:user:update",
                            path = "PUT /api/users/" + id,
                            factoryId = null;
                    switch (method) {
                        case "create" -> {
                            action = "system:user:add";
                            path = "POST /api/users";
                            factoryId = ((SnAccountCreateDTO) args[0]).factoryId();
                        }
                        case "update" -> factoryId = ((SnAccountUpdateDTO) args[1]).factoryId();
                        case "status" -> {
                            boolean disable =
                                    Boolean.FALSE.equals(((SnAccountStatusDTO) args[1]).enabled());
                            action = disable ? "USER_DISABLE" : "system:user:update";
                            path = "POST /api/users/" + id + (disable ? "/disable" : "/enable");
                        }
                        case "resetPassword" -> path = "POST /api/users/" + id + "/reset-password";
                        default -> {
                            throw new IllegalStateException("Unknown account audit action");
                        }
                    }
                    audit.record(
                            actor,
                            action,
                            path,
                            factoryId == null ? null : factoryId.strip(),
                            error);
                } catch (RuntimeException auditError) {
                    LOG.warn(
                            "SN account rejection audit unavailable exceptionType={}",
                            auditError.getClass().getSimpleName());
                }
            }
            throw error;
        }
    }
}
