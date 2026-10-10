package com.adtek.sn.service.impl;

import com.adtek.framework.audit.AuthenticationEventObserver;
import com.adtek.sn.domain.SnAuditEntity;
import com.adtek.sn.mapper.FactoryMapper;
import com.adtek.sn.mapper.FactoryMembershipMapper;
import com.adtek.sn.mapper.SnAuditMapper;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 将真实人员认证结果固定为 SN 痕迹；不读取系统账号表、请求体或凭据。 */
@Component
public class SnAuthenticationAuditObserver implements AuthenticationEventObserver {
    private final SnAuditMapper audits;
    private final FactoryMembershipMapper membership;
    private final FactoryMapper factories;

    public SnAuthenticationAuditObserver(
            SnAuditMapper audits, FactoryMembershipMapper membership, FactoryMapper factories) {
        this.audits = audits;
        this.membership = membership;
        this.factories = factories;
    }

    @Override
    public void recorded(Event event) {
        var row = new SnAuditEntity();
        row.setId(UUID.randomUUID().toString());
        row.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        row.setActorId(event.actorId());
        row.setOperator(event.actor().username());
        row.setOperatorName(event.actor().displayName());
        row.setRoles(event.actor().roles());
        row.setSource("PAGE");
        row.setAction(event.action() == Action.LOGIN ? "LOGIN" : "auth:PASSWORD_CHANGE");
        row.setResult(event.outcome() == Outcome.SUCCESS ? "SUCCESS" : "FAILED");
        row.setTraceId(event.traceId());
        row.setDetail("{}");
        if (event.action() == Action.LOGIN) {
            if (event.actorId() != null) {
                var ids = membership.factoryIds(event.actorId());
                // 原型是单厂身份；多厂主体不任选一个工厂冒充其来源。
                if (ids.size() == 1) {
                    var factory = factories.selectById(ids.getFirst());
                    if (factory != null) {
                        row.setFactoryId(factory.getId());
                        row.setFactoryCode(factory.getCode());
                        row.setFactoryName(factory.getName());
                    }
                }
            }
            row.setDetailText(
                    "client="
                            + event.clientAddress()
                            + (event.outcome() == Outcome.LOCKED
                                    ? " locked"
                                    : event.lockStarted()
                                            ? " locked=" + event.lockMinutes() + "m"
                                            : ""));
        } else {
            row.setDetailText(
                    event.outcome() == Outcome.SUCCESS
                            ? event.actor().username()
                            : "POST /api/auth/password");
        }
        row.setErrorMessage(
                switch (event.outcome()) {
                    case SUCCESS -> null;
                    case CREDENTIALS_REJECTED -> "工号或密码错误";
                    case DISABLED -> "账户已停用";
                    case LOCKED -> "登录失败次数过多，请 " + event.lockMinutes() + " 分钟后再试";
                    case PASSWORD_WRONG -> "原密码不正确";
                    case PASSWORD_SAME -> "新密码不能与原密码相同";
                    case PASSWORD_WEAK -> "密码须为 8–64 位，且同时包含字母和数字";
                });
        audits.insert(row);
        audits.creationIdentity(row);
    }
}
