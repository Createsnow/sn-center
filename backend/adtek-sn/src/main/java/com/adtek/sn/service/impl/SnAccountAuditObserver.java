package com.adtek.sn.service.impl;

import com.adtek.framework.audit.AccountAuditSnapshotPort;
import com.adtek.framework.audit.AuditEventObserver;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.sn.domain.SnAuditEntity;
import com.adtek.sn.mapper.FactoryMapper;
import com.adtek.sn.mapper.SnAuditMapper;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 人员管理成功事务投影为SN痕迹；保留同值编辑/重复重置，跳过无状态变化的启停。 */
@Component
public class SnAccountAuditObserver implements AuditEventObserver {
    private final AccountAuditSnapshotPort identities;
    private final FactoryMapper factories;
    private final SnAuditMapper audits;
    private final ObjectMapper json;

    public SnAccountAuditObserver(
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
    public void recorded(ManagementAuditPort.Event event, boolean success, ActorSnapshot actor) {
        if (!success
                || !Set.of(
                                "system:user:add",
                                "system:user:update",
                                "system:user:status",
                                "system:user:reset")
                        .contains(event.action())) return;
        if (event.action().equals("system:user:status") && !event.changes().containsKey("enabled"))
            return;
        var profile =
                identities
                        .read(Long.parseLong(event.objectId()))
                        .orElseThrow(
                                () -> new IllegalStateException("Missing committed audit account"));
        String role = role(profile.roleCodes(), profile.factoryIds());
        if (!"USER".equals(profile.principalType()) || role == null) return;
        List<String> beforeRoles =
                event.changes().containsKey("roleIds")
                        ? identities.roleCodes(ids(event, "roleIds"))
                        : profile.roleCodes();
        List<String> beforeFactories =
                event.changes().containsKey("factoryIds")
                        ? ids(event, "factoryIds")
                        : profile.factoryIds();
        String previousRole = role(beforeRoles, beforeFactories);
        if (event.action().equals("system:user:update") && previousRole == null) return;
        var row = new SnAuditEntity();
        row.setId(UUID.randomUUID().toString());
        row.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        row.setActorId(event.actorId());
        row.setOperator(actor.username());
        row.setOperatorName(actor.displayName());
        row.setRoles(actor.roles());
        if (Long.toString(event.actorId()).equals(event.objectId())
                && event.action().equals("system:user:update")) {
            Object name = before(event, "displayName");
            if (name instanceof String previousName) row.setOperatorName(previousName);
            row.setRoles(json.writeValueAsString(beforeRoles));
        }
        row.setSource("PAGE");
        row.setResult("SUCCESS");
        row.setTraceId(event.traceId());
        row.setDetail("{}");
        if (profile.factoryIds().size() == 1) {
            var factory = factories.selectById(Long.parseLong(profile.factoryIds().getFirst()));
            if (factory == null) throw new IllegalStateException("Missing committed audit factory");
            row.setFactoryId(factory.getId());
            row.setFactoryCode(factory.getCode());
            row.setFactoryName(factory.getName());
        }
        String detail = "emp_no=" + profile.username();
        String action = event.action();
        switch (action) {
            case "system:user:add" -> detail += " role=" + role;
            case "system:user:update" ->
                    detail +=
                            " role="
                                    + previousRole
                                    + "->"
                                    + role
                                    + " factory="
                                    + factory(beforeFactories)
                                    + "->"
                                    + factory(profile.factoryIds());
            case "system:user:status" ->
                    action =
                            Boolean.TRUE.equals(after(event, "enabled"))
                                    ? "USER_ENABLE"
                                    : "USER_DISABLE";
            default -> {}
        }
        row.setAction(action);
        row.setDetailText(detail);
        audits.insert(row);
        audits.creationIdentity(row);
    }

    private String factory(List<String> ids) {
        if (ids.isEmpty()) return "null";
        var factory = factories.selectById(Long.parseLong(ids.getFirst()));
        if (factory == null) throw new IllegalStateException("Missing audit factory");
        return factory.getCode();
    }

    private static Object before(ManagementAuditPort.Event event, String field) {
        return side(event, field, "before");
    }

    private static Object after(ManagementAuditPort.Event event, String field) {
        return side(event, field, "after");
    }

    private static Object side(ManagementAuditPort.Event event, String field, String side) {
        Object value = event.changes().get(field);
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> change))
            throw new IllegalStateException("Invalid account audit change");
        return change.get(side);
    }

    private static List<String> ids(ManagementAuditPort.Event event, String field) {
        Object value = before(event, field);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalStateException("Invalid account audit identities");
        return list.stream().map(String.class::cast).toList();
    }

    private static String role(List<String> roles, List<String> factories) {
        var codes = Set.copyOf(roles);
        int count = factories.size();
        if (codes.equals(Set.of("PLATFORM_ADMIN", "SN_HQ")) && count == 0) return "admin";
        if (codes.equals(Set.of("FACTORY_OPERATOR")) && count == 1) return "factory_operator";
        if (codes.equals(Set.of("SN_QUERY_ALL")) && count == 0
                || codes.equals(Set.of("SN_QUERY_FACTORY")) && count == 1) return "query";
        return null;
    }
}
