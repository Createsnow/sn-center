package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.core.*;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.sn.convert.TransferConvert;
import com.adtek.sn.domain.*;
import com.adtek.sn.mapper.TransferMapper;
import com.adtek.sn.service.support.*;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/** 单任务持有双PI占用；全部成员/护栏/状态/审计同事务，任何冲突不得留下部分转厂。 */
@Component
public class TransferEngine {
    final TransferMapper rows;
    final TransferPolicy policy;
    final TransferConvert convert;
    final AcquireEngine acquire;

    public TransferEngine(
            TransferMapper rows,
            TransferPolicy policy,
            TransferConvert convert,
            AcquireEngine acquire) {
        this.rows = rows;
        this.policy = policy;
        this.convert = convert;
        this.acquire = acquire;
    }

    static String type(String kind) {
        TransferPolicy.permission(kind);
        return "SN_TRANSFER_" + kind;
    }

    List<String> pis(String from, String to) {
        return java.util.stream.Stream.of(from, to).distinct().sorted().toList();
    }

    void reserve(TransferInput input, String task) {
        // 全局快照控制先于PI行；固定排序也适用于未来细化全局锁后的双PI竞争。
        for (String pi : pis(input.pi(), input.toPi())) {
            var counter = acquire.work.lockPi(pi);
            if (counter.activeTaskId() != null) throw new BusinessException(PI_BUSY);
            acquire.work.occupy(pi, task);
        }
    }

    void authorize(Actor actor, TaskEntity task, String kind) {
        var work = rows.workById(task.id());
        if (work == null || !kind.equals(work.kind())) throw SnPolicy.missing();
        policy.require(actor, TransferPolicy.permission(kind), work.fromFactoryId());
    }

    String execute(TaskEntity task, Actor requested) {
        var work = rows.workById(task.id());
        if (work == null || !"PENDING".equals(work.state()))
            throw new IllegalStateException("Missing pending transfer work");
        var actor =
                policy.lock(
                        requested, TransferPolicy.permission(work.kind()), work.fromFactoryId());
        var input = acquire.json.readValue(work.payload(), TransferInput.class);
        for (String pi : pis(work.fromPi(), work.toPi())) {
            if (!task.id().equals(acquire.work.lockPi(pi).activeTaskId()))
                throw new BusinessException(PI_BUSY);
        }
        try {
            if (Set.of("APPLY", "DIRECT").contains(work.kind())) create(task, work, input, actor);
            else decide(task, work, input, actor);
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(SN_CONFLICT);
        }
        if (rows.ended(task.id(), "SUCCESS", null) != 1)
            throw new IllegalStateException("Lost transfer work");
        release(work);
        return work.transferId();
    }

    private void create(
            TaskEntity task, TransferWorkEntity work, TransferInput input, Actor actor) {
        acquire.work.factory(input.factoryId());
        acquire.work.factory(input.toFactoryId());
        long quantity = rows.candidates(input).transferable();
        if (quantity == 0) throw new BusinessException(TRANSFER_NOTHING);
        acquire.work.resource(quantity);
        var from = acquire.work.factories.selectById(input.factoryId());
        var to = acquire.work.factories.selectById(input.toFactoryId());
        boolean direct = "DIRECT".equals(work.kind());
        String targetSource =
                "SNAPSHOT".equals(input.targetSource()) && rows.snapshotTarget(input) > 0
                        ? "SNAPSHOT"
                        : "MANUAL";
        var transfer =
                new TransferEntity(
                        task.id(),
                        work.kind(),
                        direct ? "APPROVED" : "PENDING",
                        1,
                        input.factoryId(),
                        from.getCode(),
                        from.getName(),
                        input.pi(),
                        input.scope(),
                        input.material(),
                        input.sn(),
                        input.toFactoryId(),
                        to.getCode(),
                        to.getName(),
                        input.toPi(),
                        input.toCustomer(),
                        input.toMaterial(),
                        targetSource,
                        input.reason(),
                        quantity,
                        actor.userId(),
                        direct ? actor.userId() : null,
                        "",
                        null,
                        direct ? Instant.now() : null,
                        acquire.numbers.next("T"),
                        acquire.names.username(actor.userId()).orElse(null),
                        direct ? acquire.names.username(actor.userId()).orElse(null) : null);
        rows.insert(transfer);
        long ordinal = 0;
        LocalDate month = null;
        String after = null;
        boolean changed =
                input.factoryId() != input.toFactoryId() || !input.pi().equals(input.toPi());
        Map<Long, RuleVersionEntity> cache = new HashMap<>();
        while (true) {
            var chunk = rows.candidateItems(input, month, after, 500);
            if (chunk.isEmpty()) break;
            collision(input.toPi(), chunk);
            var members = new ArrayList<TransferMemberEntity>();
            for (var item : chunk) {
                changed |=
                        (!input.toCustomer().isEmpty()
                                        && !input.toCustomer().equals(item.customer()))
                                || (!input.toMaterial().isEmpty()
                                        && !input.toMaterial().equals(item.material()));
                members.add(
                        new TransferMemberEntity(
                                task.id(),
                                ++ordinal,
                                item.genMonth(),
                                item.id(),
                                item.status(),
                                item.currentBatchId(),
                                item.currentPrintId(),
                                acquire.json.writeValueAsString(
                                        acquire.snapshot(item, from, cache))));
            }
            rows.members(members);
            if (direct) move(task.id(), input, chunk, false);
            else if (rows.freeze(task.id(), chunk) != chunk.size())
                throw new BusinessException(TRANSFER_CHANGED);
            var last = chunk.getLast();
            month = last.genMonth();
            after = last.id();
            acquire.progress.record(task.id(), task.executionEpoch(), ordinal, "WRITING");
        }
        if (ordinal != quantity) throw new BusinessException(TRANSFER_CHANGED);
        if (!changed) throw new BusinessException(TRANSFER_NO_CHANGE);
        fact(actor, work.kind(), transfer, "", task.id());
    }

    private void collision(String pi, List<SnCurrentEntity> members) {
        if (rows.collisions(pi, members) > 0) throw new BusinessException(SN_CONFLICT);
    }

    private void move(
            String id, TransferInput input, List<SnCurrentEntity> members, boolean pending) {
        collision(input.toPi(), members);
        // 只更新护栏当前PI；ID、分区定位和流水唯一键不删除、不重建、不抬高目标水位。
        if (rows.moveKeys(input.toPi(), members) != members.size())
            throw new BusinessException(TRANSFER_CHANGED);
        rows.exceptions(id, input, members);
        if (rows.move(id, input, members, pending) != members.size())
            throw new BusinessException(TRANSFER_CHANGED);
    }

    private void decide(
            TaskEntity task, TransferWorkEntity work, TransferInput input, Actor actor) {
        var transfer = rows.get(work.transferId());
        if (transfer == null
                || !"PENDING".equals(transfer.status())
                || transfer.version() != input.version())
            throw new BusinessException(TRANSFER_CHANGED);
        if (rows.frozenCount(transfer.id()) != transfer.quantity())
            throw new BusinessException(TRANSFER_CHANGED);
        String state =
                switch (work.kind()) {
                    case "APPROVE" -> "APPROVED";
                    case "REJECT" -> "REJECTED";
                    case "WITHDRAW" -> "WITHDRAWN";
                    default -> throw SnPolicy.invalid();
                };
        if ("APPROVE".equals(work.kind())) {
            acquire.work.factory(transfer.toFactoryId());
            var command = from(transfer, input.version(), input.note());
            long ordinal = 0;
            while (true) {
                var members = rows.page(transfer.id(), ordinal, 500);
                if (members.isEmpty()) break;
                var current = rows.current(transfer.id(), ordinal, members.getLast().ordinal());
                if (current.size() != members.size()) throw new BusinessException(TRANSFER_CHANGED);
                for (int i = 0; i < members.size(); i++) {
                    var member = members.get(i);
                    var item = current.get(i);
                    if (member.ordinal() != ++ordinal
                            || !member.itemId().equals(item.id())
                            || !"APPLYING".equals(item.status())
                            || !Objects.equals(item.factoryId(), transfer.fromFactoryId())
                            || !item.pi().equals(transfer.fromPi())
                            || !Objects.equals(item.currentBatchId(), member.fromBatchId())
                            || !Objects.equals(item.currentPrintId(), member.fromPrintId()))
                        throw new BusinessException(TRANSFER_CHANGED);
                }
                move(transfer.id(), command, current, true);
                acquire.progress.record(task.id(), task.executionEpoch(), ordinal, "WRITING");
            }
            if (ordinal != transfer.quantity()) throw new BusinessException(TRANSFER_CHANGED);
        } else if (rows.restore(transfer.id(), transfer.fromFactoryId(), transfer.fromPi())
                != transfer.quantity()) throw new BusinessException(TRANSFER_CHANGED);
        if (rows.decide(
                        transfer.id(),
                        transfer.version(),
                        state,
                        actor.userId(),
                        acquire.names.username(actor.userId()).orElse(null),
                        input.note())
                != 1) throw new BusinessException(TRANSFER_CHANGED);
        fact(actor, work.kind(), transfer, input.note(), task.id());
    }

    static TransferInput from(TransferEntity t, Long version, String note) {
        return new TransferInput(
                t.fromFactoryId(),
                t.fromPi(),
                t.scope(),
                t.material(),
                t.sn(),
                t.toFactoryId(),
                t.toPi(),
                t.toCustomer(),
                t.toMaterial(),
                t.targetSource(),
                t.reason(),
                t.id(),
                version,
                note);
    }

    void fact(Actor actor, String kind, TransferEntity transfer, String note, String taskId) {
        acquire.audit.success(
                new ManagementAuditPort.Event(
                        actor.userId(),
                        "SN转厂",
                        TransferPolicy.permission(kind),
                        "OTHER",
                        transfer.id(),
                        Map.of(
                                "quantity",
                                Long.toString(transfer.quantity()),
                                "fromFactoryId",
                                Long.toString(transfer.fromFactoryId()),
                                "toFactoryId",
                                Long.toString(transfer.toFactoryId()),
                                "fromPi",
                                transfer.fromPi(),
                                "toPi",
                                transfer.toPi(),
                                "taskId",
                                taskId,
                                "note",
                                note),
                        org.slf4j.MDC.get("traceId")));
    }

    private void release(TransferWorkEntity work) {
        for (String pi : pis(work.fromPi(), work.toPi()))
            if (acquire.work.rows.release(pi, work.taskId()) != 1)
                throw new IllegalStateException("Lost transfer PI ownership");
    }

    void ended(TaskEntity task, String state, Integer code) {
        var work = rows.workById(task.id());
        if (work == null) return;
        // 终态事务同样持快照/PI锁；仅释放本任务占用，绝不触碰已提交申请成员。
        for (String pi : pis(work.fromPi(), work.toPi())) acquire.work.lockPi(pi);
        rows.ended(task.id(), state, code);
        release(work);
        try {
            acquire.audit.failure(
                    new ManagementAuditPort.Event(
                            task.userId(),
                            "SN转厂",
                            TransferPolicy.permission(work.kind()) + ":failed",
                            "OTHER",
                            task.id(),
                            Map.of(
                                    "state",
                                    state,
                                    "errorCode",
                                    code == null ? "" : code.toString()),
                            org.slf4j.MDC.get("traceId")));
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(TransferEngine.class)
                    .warn("Transfer terminal audit unavailable; durable result retained");
        }
    }
}
