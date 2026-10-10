package com.adtek.sn.service.impl;

import com.adtek.framework.audit.*;
import com.adtek.framework.task.mapper.DurableTaskMapper;
import com.adtek.sn.domain.SnAuditEntity;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.support.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 同提交事务固定SN事实与历史身份。只读业务Mapper避免审计端口循环依赖；不读取请求体、凭据或异常文本。 */
@Component
public class SnAuditObserver implements AuditEventObserver {
    private final SnAuditMapper audits;
    private final SnAuditContextMapper context;
    private final SnWorkMapper work;
    private final AcquireMapper acquire;
    private final TransferMapper transfers;
    private final FactoryMapper factories;
    private final DurableTaskMapper tasks;
    private final ObjectMapper json;
    private final RuleMapper rules;
    private final OrderSyncMapper syncs;
    private static final Set<String> DETAILS =
            Set.of(
                    "quantity",
                    "blocked",
                    "updated",
                    "alreadyPrinted",
                    "skipped",
                    "factoryId",
                    "pi",
                    "origin",
                    "batchId",
                    "fromFactoryId",
                    "toFactoryId",
                    "fromPi",
                    "toPi",
                    "errorCode",
                    "state",
                    "format",
                    "code",
                    "name",
                    "enabled",
                    "sourceSystem",
                    "sourceAccount",
                    "orgId",
                    "orgCode",
                    "scope",
                    "prefix",
                    "suffix",
                    "base",
                    "seqLength",
                    "alphabet",
                    "startSeq",
                    "endSeq",
                    "version",
                    "mapped",
                    "count",
                    "records",
                    "snapshotVersion");

    public SnAuditObserver(
            SnAuditMapper audits,
            SnAuditContextMapper context,
            SnWorkMapper work,
            AcquireMapper acquire,
            TransferMapper transfers,
            FactoryMapper factories,
            DurableTaskMapper tasks,
            ObjectMapper json,
            RuleMapper rules,
            OrderSyncMapper syncs) {
        this.audits = audits;
        this.context = context;
        this.work = work;
        this.acquire = acquire;
        this.transfers = transfers;
        this.factories = factories;
        this.tasks = tasks;
        this.json = json;
        this.rules = rules;
        this.syncs = syncs;
    }

    @Override
    public void recorded(ManagementAuditPort.Event event, boolean success, ActorSnapshot actor) {
        if (!event.action().startsWith("sn:")) return;
        boolean factoryEvent =
                event.action().startsWith("sn:factory:create")
                        || event.action().startsWith("sn:factory:sync");
        // 失败由外层工厂用例记录一次；平台通用失败事件仍保留在平台日志。
        if (factoryEvent && !success) return;
        // 人工业务拒绝由用例外层记录；框架/SQL异常只保留平台记录，不增添原型没有的SN失败痕迹。
        if (!success && event.action().startsWith("sn:order:")) {
            if (!event.action().equals("sn:order:sync:failed")) return;
            var attempt = syncs.selectById(event.objectId());
            if (attempt == null || !"scheduled".equals(attempt.getMode())) return;
        }
        var row = new SnAuditEntity();
        row.setId(UUID.randomUUID().toString());
        row.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        row.setActorId(event.actorId());
        row.setOperator(actor.username());
        row.setOperatorName(actor.displayName());
        row.setRoles(actor.roles());
        row.setSource("SERVICE".equals(actor.principalType()) ? "MES" : "PAGE");
        row.setAction(event.action());
        row.setResult(success ? "SUCCESS" : "FAILED");
        row.setQuantity(0L);
        row.setTraceId(event.traceId());
        if (event.action().startsWith("sn:order:")) {
            orderEvent(row, event, success);
            return;
        }
        if (factoryEvent) {
            if (Boolean.TRUE.equals(event.changes().get("scheduled"))) OrderAuditText.system(row);
            factoryEvent(row, event);
            return;
        }
        if (success && Set.of("sn:rule:create", "sn:rule:update").contains(event.action())) {
            rule(row, event);
            return;
        }
        var details = new TreeMap<String, Object>();
        event.changes()
                .forEach(
                        (key, value) -> {
                            if (DETAILS.contains(key)) details.put(key, value);
                        });
        String error = Objects.toString(details.get("errorCode"), "");
        if (error.matches("[0-9]{1,10}")) row.setErrorCode(error);
        String id = event.objectId();
        boolean committed =
                success
                        && !event.action().endsWith(":submit")
                        && !event.action().endsWith(":cancel");
        if (id != null && id.matches("[0-9a-fA-F-]{36}")) {
            String operationTask = Objects.toString(event.changes().getOrDefault("taskId", id));
            var operation = tasks.operationForTask(operationTask);
            if (operation != null) row.setOperationId(operation.operationId());
            if (event.action().startsWith("sn:transfer:")) transfer(row, id, committed);
            else if (event.action().startsWith("sn:acquire:")) acquire(row, id, committed);
            else if (event.action().startsWith("sn:print:")) print(row, id, committed);
            else if (event.action().startsWith("sn:pi-init:")
                    || event.action().startsWith("sn:generation:")
                    || event.action().startsWith("sn:allocation:")) work(row, id, committed);
        } else if (id != null
                && event.action().startsWith("sn:factory:")
                && id.matches("[1-9][0-9]{0,18}")) factory(row, Long.parseLong(id));
        if (row.getQuantity() == 0 && committed) {
            String qty = Objects.toString(details.get("quantity"), "");
            if (qty.matches("[0-9]{1,18}")) row.setQuantity(Long.valueOf(qty));
        }
        // 混合客户/物料/状态保留空或实际状态集合；SN起止仅为集合边界，不代表中间每个编号均存在。
        if (event.action().startsWith("sn:transfer:")
                && event.changes().get("note") instanceof String note) details.put("note", note);
        if (row.getStartSn() != null)
            details.put(
                    "snBounds",
                    event.action().startsWith("sn:pi-init:")
                                    || event.action().startsWith("sn:generation:")
                            ? "COMMAND_BOUNDARIES"
                            : "MEMBER_MIN_MAX");
        row.setDetail(json.writeValueAsString(details));
        audits.insert(row);
        audits.creationIdentity(row);
    }

    /** 从本次持久同步记录固定数量/单号；调度展示SYSTEM，平台日志仍保留实际责任人。 */
    private void orderEvent(SnAuditEntity row, ManagementAuditPort.Event event, boolean success) {
        row.setDetail("{}");
        if (event.action().equals("sn:order:schedule")) {
            var enabled = (Map<?, ?>) event.changes().get("enabled");
            var interval = (Map<?, ?>) event.changes().get("intervalMinutes");
            row.setQuantity(null);
            row.setDetailText(
                    "enabled="
                            + pythonBoolean(enabled.get("after"))
                            + " interval_minutes="
                            + interval.get("after")
                            + " before=enabled="
                            + pythonBoolean(enabled.get("before"))
                            + " interval_minutes="
                            + interval.get("before"));
        } else {
            var attempt = syncs.selectById(event.objectId());
            if (attempt == null || attempt.getActorId() != event.actorId())
                throw new IllegalStateException("Missing order audit attempt");
            boolean scheduled = "scheduled".equals(attempt.getMode());
            row.setAction("sn:order:sync");
            if (scheduled) OrderAuditText.system(row);
            if (success) {
                row.setQuantity(attempt.getBillsCount());
                boolean single = "one".equals(attempt.getMode());
                row.setBillNo(single ? attempt.getBillNo() : null);
                row.setDetailText(
                        "mode=" + (single ? "one" : "all") + " rows=" + attempt.getRowsCount());
            } else {
                row.setQuantity(null);
                row.setDetailText("mode=scheduled");
                int code = Integer.parseInt(event.changes().get("errorCode").toString());
                row.setErrorCode(Integer.toString(code));
                row.setErrorMessage(
                        FactoryText.cut(
                                OrderAuditText.message(
                                        code,
                                        Objects.toString(event.changes().get("safeMessage"), "")),
                                1000));
            }
        }
        audits.insert(row);
        audits.creationIdentity(row);
    }

    private static String pythonBoolean(Object value) {
        return Boolean.TRUE.equals(value) ? "True" : "False";
    }

    /** 工厂新增保留名称原文、数量为空；同步保留全部组织计数与冲突清单。 */
    private void factoryEvent(SnAuditEntity row, ManagementAuditPort.Event event) {
        row.setDetail("{}");
        if (event.action().equals("sn:factory:create")) {
            var value = factories.selectById(Long.parseLong(event.objectId()));
            if (value == null) throw new IllegalStateException("Missing committed audit factory");
            row.setFactoryId(value.getId());
            row.setFactoryCode(value.getCode());
            row.setFactoryName(value.getName());
            row.setQuantity(null);
            row.setDetailText(value.getName());
        } else {
            row.setQuantity(Long.valueOf(event.changes().get("total").toString()));
            Object conflictValue = event.changes().get("conflicts");
            if (!(conflictValue instanceof List<?> conflicts)
                    || conflicts.stream().anyMatch(v -> !(v instanceof String)))
                throw new IllegalStateException("Invalid factory audit conflicts");
            row.setDetailText(
                    FactoryText.cut(
                            "inserted="
                                    + event.changes().get("inserted")
                                    + " updated="
                                    + event.changes().get("updated")
                                    + " conflicts=["
                                    + String.join(
                                            ", ",
                                            conflicts.stream().map(String.class::cast).toList())
                                    + "]",
                            2000));
        }
        audits.insert(row);
        audits.creationIdentity(row);
    }

    /** 平台仍保留字段差异；SN痕迹按真实版本修改和改名分别追加，未改变内容不追加。 */
    private void rule(SnAuditEntity row, ManagementAuditPort.Event event) {
        var rule = rules.selectById(Long.parseLong(event.objectId()));
        if (rule == null) throw new IllegalStateException("Missing committed audit rule");
        row.setQuantity(null);
        row.setDetail("{}");
        String after = changed(event, "description", "after");
        if (event.action().equals("sn:rule:create")) {
            if (after == null) throw new IllegalStateException("Missing rule audit description");
            if ("PI".equals(rule.getBindScope())) row.setPi(rule.getBindValue());
            if ("CUSTOMER".equals(rule.getBindScope())) row.setCustomer(rule.getBindValue());
            writeRule(row, "sn:rule:create", rule.getCode() + " v1 " + after);
            return;
        }
        if (after != null) {
            String action =
                    event.changes().containsKey("currentVersion")
                            ? "RULE_VERSION"
                            : "sn:rule:update";
            writeRule(
                    row,
                    action,
                    rule.getCode()
                            + " v"
                            + rule.getCurrentVersion()
                            + " "
                            + after
                            + " (was "
                            + changed(event, "description", "before")
                            + ")");
        }
        String name = changed(event, "name", "after");
        if (name != null)
            writeRule(
                    row,
                    "RULE_RENAME",
                    rule.getCode() + " " + changed(event, "name", "before") + " -> " + name);
    }

    private static String changed(ManagementAuditPort.Event event, String field, String side) {
        if (!event.changes().containsKey(field)) return null;
        Object change = event.changes().get(field);
        if (!(change instanceof Map<?, ?> values))
            throw new IllegalStateException("Invalid rule audit change");
        Object value = values.get(side);
        if (value == null) return null;
        if (!(value instanceof String text))
            throw new IllegalStateException("Invalid rule audit text");
        return text;
    }

    private void writeRule(SnAuditEntity row, String action, String detail) {
        row.setId(UUID.randomUUID().toString());
        row.setAction(action);
        row.setDetailText(
                detail.codePointCount(0, detail.length()) <= 2000
                        ? detail
                        : detail.substring(0, detail.offsetByCodePoints(0, 2000)));
        audits.insert(row);
        audits.creationIdentity(row);
    }

    private void factory(SnAuditEntity row, Long id) {
        row.setFactoryId(id);
        if (id != null) {
            var f = factories.selectById(id);
            if (f != null) {
                row.setFactoryCode(f.getCode());
                row.setFactoryName(f.getName());
            }
        }
    }

    private void bounds(SnAuditEntity row, SnAuditContextMapper.Bounds b) {
        if (b == null) return;
        row.setCustomer(b.customer());
        row.setMaterial(b.material());
        row.setBillNo(b.billNo());
        row.setStartSn(b.startSn());
        row.setEndSn(b.endSn());
        row.setBeforeStatus(b.beforeStatus());
    }

    private void work(SnAuditEntity row, String id, boolean committed) {
        var w = work.find(id);
        if (w == null) return;
        factory(row, w.factoryId());
        row.setPi(w.pi());
        row.setQuantity(committed ? w.quantity() : 0L);
        var input = json.readValue(w.payload(), SnWorkInput.class);
        row.setCustomer(input.customer());
        row.setMaterial(input.material());
        if (input.plan() != null) {
            row.setBillNo(input.plan().bill().getBillNo());
            row.setCustomer(input.plan().bill().getCustomer());
            var materials =
                    input.plan().segments().stream()
                            .map(SnWorkInput.Segment::material)
                            .distinct()
                            .toList();
            row.setMaterial(materials.size() == 1 ? materials.getFirst() : null);
        }
        row.setStartSn(w.firstSn());
        row.setEndSn(w.lastSn());
        if ("ALLOCATE".equals(w.kind())) {
            row.setBeforeStatus("PENDING_ALLOC");
            if (committed) {
                bounds(row, context.allocation(id));
                // 分配归属取整单首次客户；成员可能来自不同客户/PI的后续生成。
                row.setCustomer(input.customer());
            }
        }
        // 生成允许无关 ERP 字段变化；成功事务仍持控制锁，客户应与本次实际写入的 SN 一致。
        // 只在提交前固定该值，之后的历史查询不再从订单快照重建。
        if (committed && "GENERATE".equals(w.kind()))
            row.setCustomer(work.bill(input.billId()).getCustomer());
        if (committed)
            row.setAfterStatus(
                    switch (w.kind()) {
                        case "PI_INIT" -> "PRINTED";
                        case "GENERATE" -> "PENDING_ALLOC";
                        case "ALLOCATE" -> "TO_ACQUIRE";
                        default -> throw new IllegalStateException("Unknown SN audit work");
                    });
    }

    private void acquire(SnAuditEntity row, String id, boolean committed) {
        var w = acquire.workById(id);
        if (w == null) return;
        factory(row, w.factoryId());
        row.setPi(w.pi());
        row.setSource(w.origin());
        row.setOperationId(w.operationId());
        row.setBatchId(w.batchId());
        row.setQuantity(committed ? ("TAKE".equals(w.kind()) ? w.eligible() : w.updatedQty()) : 0L);
        if (w.batchId() != null) {
            var b = acquire.batchById(w.batchId());
            if (b != null) {
                row.setFactoryCode(b.factoryCode());
                row.setFactoryName(b.factoryName());
            }
            if ("TAKE".equals(w.kind())) bounds(row, context.batch(w.batchId()));
        }
        row.setBeforeStatus("TAKE".equals(w.kind()) ? "TO_ACQUIRE" : "TO_PRINT");
        if (committed) row.setAfterStatus("TAKE".equals(w.kind()) ? "TO_PRINT" : "PRINTED");
    }

    private void print(SnAuditEntity row, String id, boolean committed) {
        var p = acquire.printByTask(id);
        if (p == null) return;
        row.setFactoryId(p.factoryId());
        row.setFactoryCode(p.factoryCode());
        row.setFactoryName(p.factoryName());
        row.setPi(p.pi());
        row.setOperationId(p.operationId());
        row.setQuantity(committed ? p.quantity() : 0L);
        bounds(row, context.print(p.id()));
        if (committed) row.setAfterStatus("PRINTED");
        else if (row.getAction().equals("sn:print:file:failed")) {
            row.setBeforeStatus("PRINTED");
            row.setAfterStatus("PRINTED");
        }
    }

    private void transfer(SnAuditEntity row, String id, boolean committed) {
        var t = transfers.get(id);
        var w = transfers.workById(id);
        if (t == null && w != null && w.transferId() != null) t = transfers.get(w.transferId());
        if (t != null) {
            row.setTransferId(t.id());
            row.setFactoryId(t.fromFactoryId());
            row.setFactoryCode(t.fromFactoryCode());
            row.setFactoryName(t.fromFactoryName());
            row.setPi(t.fromPi());
            row.setToFactoryId(t.toFactoryId());
            row.setToFactoryCode(t.toFactoryCode());
            row.setToPi(t.toPi());
            row.setReason(t.reason());
            row.setQuantity(committed ? t.quantity() : 0L);
            bounds(row, context.transfer(t.id()));
            String action = row.getAction();
            if (committed) {
                if (action.endsWith(":apply")) row.setAfterStatus("APPLYING");
                else if (action.endsWith(":approve") || action.endsWith(":direct"))
                    row.setAfterStatus("TO_ACQUIRE");
                else row.setAfterStatus(row.getBeforeStatus());
                if (!action.endsWith(":apply") && !action.endsWith(":direct"))
                    row.setBeforeStatus("APPLYING");
            }
        } else if (w != null) {
            var input = json.readValue(w.payload(), TransferInput.class);
            factory(row, w.fromFactoryId());
            row.setPi(w.fromPi());
            row.setToFactoryId(input.toFactoryId());
            var f = factories.selectById(input.toFactoryId());
            if (f != null) row.setToFactoryCode(f.getCode());
            row.setToPi(w.toPi());
            row.setReason(input.reason());
        }
    }
}
