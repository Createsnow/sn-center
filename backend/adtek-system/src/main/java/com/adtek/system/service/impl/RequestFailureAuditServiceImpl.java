package com.adtek.system.service.impl;
import com.adtek.framework.audit.RequestFailureAuditPort;
import com.adtek.system.mapper.OperLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 已拒绝请求独立提交；只查可信主体的非秘密身份快照，绝不记录提交的工号或凭据。 */
@Service
public class RequestFailureAuditServiceImpl implements RequestFailureAuditPort {
    private final OperLogMapper logs;
    public RequestFailureAuditServiceImpl(OperLogMapper logs) { this.logs=logs; }
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(Event event) {
        var actor=event.actorId()==null?null:logs.actorSnapshot(event.actorId());
        logs.requestFailure(event,actor==null?null:actor.departmentId(),actor==null?null:actor.username());
    }
}
