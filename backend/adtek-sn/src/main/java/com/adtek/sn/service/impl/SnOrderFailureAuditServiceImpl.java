package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.audit.AccountAuditSnapshotPort;
import com.adtek.sn.domain.SnAuditEntity;
import com.adtek.sn.mapper.SnAuditMapper;
import com.adtek.sn.service.SnOrderFailureAuditService;
import com.adtek.sn.service.support.FactoryText;
import com.adtek.sn.service.support.OrderAuditText;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 业务拒绝与原型的POST/PUT路由一致；成功写入失败不能伪装成业务拒绝。 */
@Service
public class SnOrderFailureAuditServiceImpl implements SnOrderFailureAuditService {
    private final AccountAuditSnapshotPort identities;
    private final SnAuditMapper audits;
    private final ObjectMapper json;

    public SnOrderFailureAuditServiceImpl(
            AccountAuditSnapshotPort identities, SnAuditMapper audits, ObjectMapper json) {
        this.identities = identities;
        this.audits = audits;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long actorId, boolean schedule, String billNo, BusinessException error) {
        var actor = identities.read(actorId).orElse(null);
        if (actor == null || !"USER".equals(actor.principalType())) return;
        String bill = FactoryText.trim(billNo);
        if (bill.codePointCount(0, bill.length()) > 64) return;
        var row = new SnAuditEntity();
        row.setId(UUID.randomUUID().toString());
        row.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        row.setActorId(actorId);
        row.setOperator(actor.username());
        row.setOperatorName(actor.displayName());
        row.setRoles(json.writeValueAsString(actor.roleCodes()));
        row.setSource("PAGE");
        row.setAction(schedule ? "sn:order:schedule" : "sn:order:sync");
        row.setResult("FAILED");
        row.setBillNo(bill.isEmpty() ? null : bill);
        row.setTraceId(MDC.get("traceId"));
        row.setDetail("{}");
        row.setDetailText(schedule ? "PUT /api/orders/sync-schedule" : "POST /api/orders/sync");
        row.setErrorCode(Integer.toString(error.getError().code()));
        row.setErrorMessage(FactoryText.cut(OrderAuditText.message(error), 1000));
        audits.insert(row);
        audits.creationIdentity(row);
    }
}
