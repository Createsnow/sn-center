package com.adtek.sn.service.impl;

import com.adtek.common.annotation.Idempotent;
import com.adtek.common.core.*;
import com.adtek.framework.security.CommandValidator;
import com.adtek.framework.task.*;
import com.adtek.framework.task.mapper.DurableFileMapper;
import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.convert.SnQueryConvert;
import com.adtek.sn.domain.*;
import com.adtek.sn.dto.SnExportDTO;
import com.adtek.sn.mapper.SnQueryMapper;
import com.adtek.sn.query.SnItemQuery;
import com.adtek.sn.service.SnQueryService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class SnQueryServiceImpl implements SnQueryService {
    final SnQueryAccess access;
    final SnQueryMapper rows;
    final SnQueryPolicy policy;
    final AcquireEngine acquire;
    final SnQueryConvert convert;
    final DurableTaskService tasks;
    final DurableFileMapper files;
    final SnCursor cursors;
    final CommandValidator validator;

    public SnQueryServiceImpl(
            SnQueryAccess access,
            SnQueryMapper rows,
            SnQueryPolicy policy,
            AcquireEngine acquire,
            SnQueryConvert convert,
            DurableTaskService tasks,
            DurableFileMapper files,
            SnCursor cursors,
            CommandValidator validator) {
        this.access = access;
        this.rows = rows;
        this.policy = policy;
        this.acquire = acquire;
        this.convert = convert;
        this.tasks = tasks;
        this.files = files;
        this.cursors = cursors;
        this.validator = validator;
    }

    SnItemQuery normalized(SnItemQuery q) {
        validator.requireValid(q);
        String mode = q.materialMode() == null ? "ALL" : q.materialMode();
        acquire.policy.material(mode, q.material());
        String status = SnPolicy.optional(q.status());
        if (status != null
                && !Set.of("PENDING_ALLOC", "TO_ACQUIRE", "TO_PRINT", "PRINTED", "APPLYING")
                        .contains(status)) throw SnPolicy.invalid();
        String factory = SnPolicy.optional(q.factoryId()), batch = SnPolicy.optional(q.batchId());
        if (factory != null) SnPolicy.id(factory);
        if (batch != null) batch = TaskAccess.uuid(batch);
        return new SnItemQuery(
                factory,
                SnPolicy.optional(q.pi()),
                SnPolicy.optional(q.customer()),
                mode,
                "VALUE".equals(mode) ? q.material() : null,
                status,
                SnPolicy.optional(q.sn()),
                SnPolicy.optional(q.billNo()),
                batch,
                q.piAll(),
                SnPolicy.optional(q.batchNo()));
    }

    SnQueryItemVO view(SnQueryItemEntity row, Map<Long, RuleVersionEntity> cache) {
        String text = null;
        if (row.seqDec() != null && row.ruleVersionId() != null) {
            var version = cache.computeIfAbsent(row.ruleVersionId(), acquire.versions::selectById);
            if (version == null)
                throw new IllegalStateException("Missing immutable encoding version");
            var spec = SnWorkEngine.spec(version);
            text =
                    SequenceCodec.format(
                            row.seqDec(),
                            new SequenceSpec("", "", spec.base(), spec.length(), spec.alphabet()));
        }
        return convert.item(row, text);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CursorResult<SnQueryItemVO> items(SnItemQuery query, String cursor, int size) {
        if (size < 1 || size > 1000) throw SnPolicy.invalid();
        var q = normalized(query);
        var actor = policy.read(SnQueryPolicy.VIEW);
        var scope = policy.scope(actor, SnQueryPolicy.VIEW, q);
        String binding = cursors.binding(actor, "sn-query:" + acquire.json.writeValueAsString(q));
        var position = cursors.read(cursor, binding);
        var result = rows.items(scope, q, position.month(), position.id(), size + 1);
        boolean more = result.size() > size;
        var page = result.stream().limit(size).toList();
        Map<Long, RuleVersionEntity> cache = new HashMap<>();
        return new CursorResult<>(
                page.stream().map(row -> view(row, cache)).toList(),
                more
                        ? cursors.next(binding, 0, page.getLast().genMonth(), page.getLast().id())
                        : null);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SnQueryPageVO page(SnItemQuery query, int page, int size) {
        if (page < 1 || size < 1 || size > 1000) throw SnPolicy.invalid();
        var q = normalized(query);
        var actor = policy.read(SnQueryPolicy.VIEW);
        var scope = policy.scope(actor, SnQueryPolicy.VIEW, q);
        long total = rows.cappedCount(scope, q);
        Map<Long, RuleVersionEntity> cache = new HashMap<>();
        return new SnQueryPageVO(
                rows.page(scope, q, (long) (page - 1) * size, size).stream()
                        .map(row -> view(row, cache))
                        .toList(),
                Long.toString(Math.min(total, 100000)),
                page,
                size,
                total > 100000);
    }

    @Override
    @Transactional(readOnly = true)
    public SnCountVO count(SnItemQuery query) {
        var q = normalized(query);
        var actor = policy.read(SnQueryPolicy.VIEW);
        return new SnCountVO(
                "TOTAL", Long.toString(rows.count(policy.scope(actor, SnQueryPolicy.VIEW, q), q)));
    }

    static String format(String value) {
        if (!Set.of("CSV", "XLSX").contains(value)) throw SnPolicy.invalid();
        return value;
    }

    static String type(String format) {
        return "SN_QUERY_" + format(format);
    }

    @Override
    @Idempotent
    public TaskVO export(SnExportDTO command) {
        validator.requireValid(command);
        String format = format(command.format());
        var q = normalized(command.filters());
        var actor = policy.read(SnQueryPolicy.EXPORT);
        policy.require(actor, SnQueryPolicy.VIEW);
        String payload = acquire.json.writeValueAsString(q);
        String canonical =
                acquire.json.writeValueAsString(
                        new TreeMap<>(
                                Map.of(
                                        "v",
                                        1,
                                        "format",
                                        format,
                                        "filtersDigest",
                                        TaskDigests.sha256(payload))));
        return tasks.submit(
                type(format),
                command.operationId(),
                canonical,
                TaskDigests.sha256(actor.userId() + ":" + actor.securityVersion() + ":" + payload),
                task -> {
                    // 所有SN/目录写持该授权协调和快照控制；分块读取期间不允许业务成员变化。
                    var fresh = access.authorization.lockCurrent(actor);
                    acquire.work.control.lock();
                    var scope =
                            policy.intersection(
                                    policy.scope(fresh, SnQueryPolicy.VIEW, q),
                                    policy.scope(fresh, SnQueryPolicy.EXPORT, q));
                    long total = rows.count(scope, q);
                    acquire.work.resource(total);
                    long ordinal = 0;
                    long after = 0;
                    Map<Long, RuleVersionEntity> cache = new HashMap<>();
                    rows.export(
                            new SnQueryExportEntity(
                                    task.id(), fresh.userId(), format, payload, total, null, 2));
                    while (true) {
                        var chunk = rows.exportItems(scope, q, after, 500);
                        if (chunk.isEmpty()) break;
                        var fixed = new ArrayList<SnExportRowEntity>();
                        for (var row : chunk)
                            fixed.add(
                                    new SnExportRowEntity(
                                            task.id(),
                                            ++ordinal,
                                            row.factoryId(),
                                            row.pi(),
                                            acquire.json.writeValueAsString(
                                                    SnQueryFiles.prototypeCells(
                                                            ordinal, view(row, cache)))));
                        rows.rows(fixed);
                        var last = chunk.getLast();
                        after = last.creationOrder();
                    }
                    if (ordinal != total)
                        throw new IllegalStateException("Inconsistent SN export snapshot");
                    String extension = format.toLowerCase(Locale.ROOT);
                    files.insert(task.id(), task.id() + "." + extension, total);
                    files.metadata(
                            task.id(),
                            "sn_export." + extension,
                            format.equals("CSV") ? "text/csv;charset=UTF-8" : PrintFiles.XLSX);
                    tasks.confirmBusiness(task.id(), task.id());
                    acquire.audit.success(
                            new com.adtek.framework.audit.ManagementAuditPort.Event(
                                    fresh.userId(),
                                    "SN查询导出",
                                    "sn:query:export:submit",
                                    "OTHER",
                                    task.id(),
                                    Map.of("quantity", Long.toString(total), "format", format),
                                    org.slf4j.MDC.get("traceId")));
                });
    }

    @Override
    public TaskVO operation(String format, String operation) {
        return tasks.operation(type(format), operation);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<SnExportVO> exports(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw SnPolicy.invalid();
        var actor = policy.read(SnQueryPolicy.EXPORT);
        policy.require(actor, SnQueryPolicy.VIEW);
        return new PageResult<>(
                rows.history(actor.userId(), (long) (page - 1) * size, size).stream()
                        .map(
                                row -> {
                                    access.authorize(actor, row.taskId());
                                    var file = files.find(row.taskId());
                                    if (file == null)
                                        throw new IllegalStateException("Missing export file");
                                    return convert.export(row, file.state().name());
                                })
                        .toList(),
                Long.toString(rows.historyCount(actor.userId())),
                page,
                size);
    }
}
