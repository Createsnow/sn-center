package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.core.*;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.framework.task.service.TaskProgressService;
import com.adtek.sn.convert.AcquireConvert;
import com.adtek.sn.domain.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.support.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.*;

/** 持任务代次/安全/快照/PI控制锁后执行；批量SQL与固定事实不分段提交。 */
@Component
public class AcquireEngine {
    final AcquireMapper rows;
    final AcquirePolicy policy;
    final SnWorkEngine work;
    final ObjectMapper json;
    final AcquireConvert convert;
    final RuleVersionMapper versions;
    final TaskProgressService progress;
    final ManagementAuditPort audit;

    public AcquireEngine(
            AcquireMapper rows,
            AcquirePolicy policy,
            SnWorkEngine work,
            ObjectMapper json,
            AcquireConvert convert,
            RuleVersionMapper versions,
            TaskProgressService progress,
            ManagementAuditPort audit) {
        this.rows = rows;
        this.policy = policy;
        this.work = work;
        this.json = json;
        this.convert = convert;
        this.versions = versions;
        this.progress = progress;
        this.audit = audit;
    }

    static String type(String kind) {
        return switch (kind) {
            case "TAKE" -> "SN_ACQUIRE";
            case "CALLBACK" -> "SN_CALLBACK";
            default -> throw SnPolicy.invalid();
        };
    }

    static String permission(String kind) {
        return switch (kind) {
            case "TAKE" -> AcquirePolicy.TAKE;
            case "CALLBACK" -> AcquirePolicy.CALLBACK;
            default -> throw SnPolicy.invalid();
        };
    }

    void authorize(Actor actor, TaskEntity task, String kind) {
        var input = rows.workById(task.id());
        if (input == null || !input.kind().equals(kind)) throw SnPolicy.missing();
        policy.require(actor, permission(kind), input.factoryId());
    }

    String execute(TaskEntity task, Actor requested) {
        var input = rows.workById(task.id());
        var actor = policy.lock(requested, permission(input.kind()), input.factoryId());
        var counter = work.lockPi(input.pi());
        if (!task.id().equals(counter.activeTaskId())) throw new BusinessException(PI_BUSY);
        work.factory(input.factoryId());
        if ("TAKE".equals(input.kind())) take(task, input, actor);
        else callback(task, input, actor);
        if (work.rows.release(input.pi(), task.id()) != 1)
            throw new IllegalStateException("Lost PI control");
        return task.id();
    }

    private void take(TaskEntity task, AcquireWorkEntity input, Actor actor) {
        long total = rows.count(input.factoryId(), input.pi(), "TO_ACQUIRE");
        work.resource(total);
        var factory = work.factories.selectById(input.factoryId());
        long scanned = 0, eligible = 0;
        LocalDate month = null;
        String after = null;
        Map<Long, RuleVersionEntity> cache = new HashMap<>();
        while (true) {
            var chunk =
                    rows.candidates(input.factoryId(), input.pi(), "TO_ACQUIRE", month, after, 500);
            if (chunk.isEmpty()) break;
            var acquired = new ArrayList<SnCurrentEntity>();
            var members = new ArrayList<AcquireMemberEntity>();
            var outcomes = new ArrayList<AcquireResultEntity>();
            for (var row : chunk) {
                scanned++;
                if ("ELIGIBLE".equals(row.eligibility())) {
                    eligible++;
                    acquired.add(row);
                    members.add(
                            new AcquireMemberEntity(
                                    task.id(),
                                    eligible,
                                    row.genMonth(),
                                    row.id(),
                                    row.sn(),
                                    row.seqPi(),
                                    row.seqDec(),
                                    json.writeValueAsString(snapshot(row, factory, cache))));
                }
                outcomes.add(
                        new AcquireResultEntity(
                                task.id(),
                                scanned,
                                row.sn(),
                                "ELIGIBLE".equals(row.eligibility())
                                        ? "ACQUIRED"
                                        : row.eligibility()));
            }
            if (!members.isEmpty()) {
                rows.memberBatch(members);
                if (rows.acquireBatch(acquired, task.id()) != members.size())
                    throw new BusinessException(ACQUIRE_CHANGED);
            }
            rows.resultBatch(outcomes);
            var last = chunk.getLast();
            month = last.genMonth();
            after = last.id();
            progress.record(task.id(), task.executionEpoch(), scanned, "WRITING");
        }
        if (scanned != total) throw new BusinessException(ACQUIRE_CHANGED);
        if (eligible > 0)
            rows.batch(
                    new AcquireBatchEntity(
                            task.id(),
                            input.factoryId(),
                            factory.getCode(),
                            factory.getName(),
                            input.pi(),
                            eligible,
                            input.actorId(),
                            input.origin(),
                            input.operationId(),
                            null));
        rows.complete(
                task.id(),
                total,
                eligible,
                total - eligible,
                eligible,
                0,
                eligible > 0 ? task.id() : null);
        fact(
                actor,
                "sn:acquire:take",
                task.id(),
                Map.of(
                        "quantity",
                        Long.toString(eligible),
                        "blocked",
                        Long.toString(total - eligible),
                        "factoryId",
                        Long.toString(input.factoryId()),
                        "pi",
                        input.pi(),
                        "origin",
                        input.origin()));
    }

    private void callback(TaskEntity task, AcquireWorkEntity input, Actor actor) {
        var command = json.readValue(input.payload(), AcquireInput.class);
        long ordinal = 0, updated = 0, already = 0;
        var batch = rows.batchById(command.batchId());
        if (batch == null) throw SnPolicy.missing();
        for (int at = 0; at < command.sns().size(); at += 500) {
            var requested = command.sns().subList(at, Math.min(at + 500, command.sns().size()));
            var members = new HashMap<String, AcquireMemberEntity>();
            rows.membersBySn(command.batchId(), requested)
                    .forEach(member -> members.put(member.sn(), member));
            var current = new HashMap<String, SnCurrentEntity>();
            rows.currentFor(command.batchId(), requested)
                    .forEach(row -> current.put(row.id(), row));
            var changed = new ArrayList<SnCurrentEntity>();
            var outcomes = new ArrayList<AcquireResultEntity>();
            for (String sn : requested) {
                var member = members.get(sn);
                var row = member == null ? null : current.get(member.itemId());
                String outcome = callbackOutcome(batch, member, row);
                if ("UPDATED".equals(outcome)) {
                    updated++;
                    changed.add(row);
                } else if ("ALREADY_PRINTED".equals(outcome)) already++;
                outcomes.add(new AcquireResultEntity(task.id(), ++ordinal, sn, outcome));
            }
            if (!changed.isEmpty() && rows.printedBatch(changed, task.id()) != changed.size())
                throw new BusinessException(ACQUIRE_CHANGED);
            rows.resultBatch(outcomes);
            progress.record(task.id(), task.executionEpoch(), ordinal, "WRITING");
        }
        rows.complete(
                task.id(),
                ordinal,
                0,
                ordinal - updated - already,
                updated,
                already,
                command.batchId());
        fact(
                actor,
                "sn:acquire:callback",
                task.id(),
                Map.of(
                        "updated",
                        Long.toString(updated),
                        "alreadyPrinted",
                        Long.toString(already),
                        "skipped",
                        Long.toString(ordinal - updated - already),
                        "batchId",
                        batch.id(),
                        "origin",
                        input.origin()));
    }

    static String callbackOutcome(
            AcquireBatchEntity batch, AcquireMemberEntity member, SnCurrentEntity row) {
        if (member == null) return "NOT_IN_BATCH";
        if (row == null) return "MISSING";
        if (!Objects.equals(row.factoryId(), batch.factoryId()) || !row.pi().equals(batch.pi()))
            return "TRANSFERRED";
        if ("APPLYING".equals(row.status())) return "APPLYING";
        if (!batch.id().equals(row.currentBatchId())) return "BATCH_CHANGED";
        if ("PRINTED".equals(row.status())) return "ALREADY_PRINTED";
        return "TO_PRINT".equals(row.status()) ? "UPDATED" : "STATE_CHANGED";
    }

    String seqText(SnCurrentEntity row, Map<Long, RuleVersionEntity> cache) {
        if (row.seqDec() == null || row.ruleVersionId() == null) return null;
        var version = cache.computeIfAbsent(row.ruleVersionId(), versions::selectById);
        if (version == null) throw new IllegalStateException("Missing immutable rule version");
        var spec = SnWorkEngine.spec(version);
        return SequenceCodec.format(
                row.seqDec(),
                new SequenceSpec("", "", spec.base(), spec.length(), spec.alphabet()));
    }

    AcquireSnapshot snapshot(
            SnCurrentEntity row, FactoryEntity factory, Map<Long, RuleVersionEntity> cache) {
        return convert.snapshot(row, factory, seqText(row, cache));
    }

    void fact(Actor actor, String action, String object, Map<String, Object> changes) {
        audit.success(
                new ManagementAuditPort.Event(
                        actor.userId(),
                        "SN领取打印",
                        action,
                        "OTHER",
                        object,
                        changes,
                        org.slf4j.MDC.get("traceId")));
    }

    void ended(TaskEntity task, String state, Integer code) {
        var input = rows.workById(task.id());
        if (input == null) return;
        rows.ended(task.id(), state, code);
        work.rows.release(input.pi(), task.id());
        try {
            audit.failure(
                    new ManagementAuditPort.Event(
                            input.actorId(),
                            "SN领取打印",
                            permission(input.kind()),
                            "OTHER",
                            task.id(),
                            Map.of(
                                    "state",
                                    state,
                                    "errorCode",
                                    code == null ? "" : code.toString()),
                            org.slf4j.MDC.get("traceId")));
        } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(AcquireEngine.class)
                    .warn(
                            "SN terminal audit unavailable traceId={} exceptionType={}",
                            org.slf4j.MDC.get("traceId"),
                            error.getClass().getSimpleName());
        }
    }
}
