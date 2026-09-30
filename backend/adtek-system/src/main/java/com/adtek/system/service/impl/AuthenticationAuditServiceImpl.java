package com.adtek.system.service.impl;

import com.adtek.system.mapper.OperLogMapper;
import com.adtek.system.service.AuthenticationAuditService;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

@Service
public class AuthenticationAuditServiceImpl implements AuthenticationAuditService {
    private final OperLogMapper logs;
    private final ObjectMapper json;
    public AuthenticationAuditServiceImpl(OperLogMapper logs, ObjectMapper json) { this.logs = logs; this.json = json; }

    @Override
    @Transactional
    public void recordSuccess(long userId, Action action) {
        var actor=logs.actorSnapshot(userId);
        logs.insert(userId, "auth:" + action.name(), Long.toString(userId), true, "{}", MDC.get("traceId"),actor==null?null:actor.departmentId(),actor==null?null:actor.username());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure() {
        logs.insert(null, "auth:LOGIN", null, false, "{\"reason\":\"credentialsRejected\"}", MDC.get("traceId"),null,null);
    }

    @Override
    @Transactional
    public void recordLanguageChange(long userId, String previous, String current) {
        String changes = json.writeValueAsString(Map.of("language", Map.of("before", previous, "after", current)));
        var actor=logs.actorSnapshot(userId);
        logs.insert(userId, "auth:LANGUAGE_CHANGE", Long.toString(userId), true, changes, MDC.get("traceId"),actor==null?null:actor.departmentId(),actor==null?null:actor.username());
    }
}
