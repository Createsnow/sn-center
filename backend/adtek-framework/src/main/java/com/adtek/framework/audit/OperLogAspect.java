package com.adtek.framework.audit;

import com.adtek.common.annotation.OperLog;
import com.adtek.common.core.BusinessException;
import com.adtek.framework.security.CurrentActor;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 位于事务拦截器内部：成功审计失败会回滚业务，拒绝日志只记录受控错误码。 */
@Aspect
@Component
@Order(200)
public class OperLogAspect {
    private static final Logger LOG = LoggerFactory.getLogger(OperLogAspect.class);
    private final ManagementAuditPort audit;
    private final CurrentActor actors;
    public OperLogAspect(ManagementAuditPort audit, CurrentActor actors) { this.audit = audit; this.actors = actors; }
    @Around("@annotation(operation)")
    public Object record(ProceedingJoinPoint call, OperLog operation) throws Throwable {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Management audit requires transaction"); }
        long actorId = actors.requireAuthenticated().userId();
        AuditContext.begin(java.util.Set.of(operation.fields()));
        try {
            Object result = call.proceed();
            var change = AuditContext.get();
            if (change.objectId() == null) { throw new IllegalStateException("Missing management audit snapshot"); }
            audit.success(new ManagementAuditPort.Event(actorId, operation.title(), operation.action(), operation.businessType().name(),
                    change.objectId(), change.diff(), MDC.get("traceId")));
            return result;
        } catch (Throwable error) {
            String code = error instanceof BusinessException business ? Integer.toString(business.getError().code()) : "internal";
            try { audit.failure(new ManagementAuditPort.Event(actorId, operation.title(), operation.action(), operation.businessType().name(),
                    AuditContext.get().objectId(), Map.of("errorCode", code), MDC.get("traceId"))); RequestAuditState.failureCommitted(); }
            catch (RuntimeException auditError) {
                LOG.error("Management rejection audit unavailable traceId={} exceptionType={}",
                        MDC.get("traceId"), auditError.getClass().getSimpleName());
            }
            throw error;
        } finally { AuditContext.clear(); }
    }
}
