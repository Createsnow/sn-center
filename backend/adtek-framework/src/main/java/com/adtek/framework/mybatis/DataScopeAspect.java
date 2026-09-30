package com.adtek.framework.mybatis;

import com.adtek.common.annotation.DataScope;
import com.adtek.framework.security.CurrentActor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(50)
public class DataScopeAspect {
    private final CurrentActor actors;
    public DataScopeAspect(CurrentActor actors) { this.actors = actors; }
    @Around("@annotation(scope)")
    public Object apply(ProceedingJoinPoint call, DataScope scope) throws Throwable {
        try (var ignored = ScopeContext.open(scope.resource(), scope.permission(), actors.requirePermission(scope.permission()))) {
            return call.proceed();
        }
    }
}
