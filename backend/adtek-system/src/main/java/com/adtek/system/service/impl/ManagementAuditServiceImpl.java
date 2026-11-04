package com.adtek.system.service.impl;

import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.system.mapper.OperLogMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.ObjectMapper;

@Service
public class ManagementAuditServiceImpl implements ManagementAuditPort {
    private final OperLogMapper logs;
    private final ObjectMapper json;
    private final java.util.List<com.adtek.framework.audit.AuditEventObserver> observers;

    public ManagementAuditServiceImpl(
            OperLogMapper logs,
            ObjectMapper json,
            java.util.List<com.adtek.framework.audit.AuditEventObserver> observers) {
        this.logs = logs;
        this.json = json;
        this.observers = java.util.List.copyOf(observers);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void success(Event event) {
        insert(event, true);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(Event event) {
        insert(event, false);
    }

    private void insert(Event event, boolean success) {
        var actor = logs.actorSnapshot(event.actorId());
        logs.management(
                event.actorId(),
                event.module(),
                event.businessType(),
                event.action(),
                event.objectId(),
                success,
                json.writeValueAsString(event.changes()),
                event.traceId(),
                actor == null ? null : actor.departmentId(),
                actor == null ? null : actor.username());
        var snapshot =
                new com.adtek.framework.audit.AuditEventObserver.ActorSnapshot(
                        actor == null ? "" : actor.username(),
                        actor == null ? "" : actor.displayName(),
                        actor == null ? "" : actor.roles(),
                        actor == null ? "USER" : actor.principalType());
        for (var observer : observers) observer.recorded(event, success, snapshot);
    }
}
