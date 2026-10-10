package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.audit.AccountAuditSnapshotPort;
import com.adtek.sn.domain.SnAuditEntity;
import com.adtek.sn.mapper.FactoryMapper;
import com.adtek.sn.mapper.SnAuditMapper;
import com.adtek.sn.service.SnFactoryFailureAuditService;
import com.adtek.sn.service.support.FactoryText;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 原型工厂失败统一记FACTORY_SYNC，详情使用原型路由；超长编码不能写入原型痕迹列。 */
@Service
public class SnFactoryFailureAuditServiceImpl implements SnFactoryFailureAuditService {
    private final AccountAuditSnapshotPort identities;
    private final FactoryMapper factories;
    private final SnAuditMapper audits;
    private final ObjectMapper json;

    public SnFactoryFailureAuditServiceImpl(
            AccountAuditSnapshotPort identities,
            FactoryMapper factories,
            SnAuditMapper audits,
            ObjectMapper json) {
        this.identities = identities;
        this.factories = factories;
        this.audits = audits;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long actorId, boolean synchronize, String code, BusinessException error) {
        String clean = FactoryText.trim(code);
        if (clean.codePointCount(0, clean.length()) > 64) return;
        var actor = identities.read(actorId).orElse(null);
        if (actor == null || !"USER".equals(actor.principalType())) return;
        var row = new SnAuditEntity();
        row.setId(UUID.randomUUID().toString());
        row.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        row.setActorId(actorId);
        row.setOperator(actor.username());
        row.setOperatorName(actor.displayName());
        row.setRoles(json.writeValueAsString(actor.roleCodes()));
        row.setSource("PAGE");
        row.setAction("sn:factory:sync");
        row.setResult("FAILED");
        row.setTraceId(MDC.get("traceId"));
        row.setDetail("{}");
        row.setDetailText("POST /api/factories" + (synchronize ? "/sync" : ""));
        if (!clean.isEmpty()) {
            row.setFactoryCode(clean);
            var factory = factories.byCode(clean);
            if (factory != null) {
                row.setFactoryId(factory.getId());
                row.setFactoryName(factory.getName());
            }
        }
        row.setErrorCode(Integer.toString(error.getError().code()));
        row.setErrorMessage(FactoryText.cut(message(error), 1000));
        audits.insert(row);
        audits.creationIdentity(row);
    }

    private static String message(BusinessException error) {
        return switch (error.getError().code()) {
            case 1004000033 -> "工厂编码与名称必填，编码不超过 64 位";
            case 1004000003 -> "工厂编码或名称已存在：" + error.getParams().getOrDefault("factory", "");
            case 1005000002 -> "金蝶登录失败：" + error.getParams().getOrDefault("message", "");
            case 1005000006 -> "金蝶查询失败：" + error.getParams().getOrDefault("message", "");
            case 1000000004, 1003000004, 1002000011 -> "当前角色无权执行该操作";
            case 1000000008, 1002000008 -> "数据正被其他操作占用，请稍后重试";
            default -> error.getError().message();
        };
    }
}
