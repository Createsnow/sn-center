package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.annotation.Idempotent;
import com.adtek.common.core.*;
import com.adtek.framework.security.CommandValidator;
import com.adtek.framework.task.*;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.convert.SnConvert;
import com.adtek.sn.domain.*;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.service.SnWorkService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class SnWorkServiceImpl implements SnWorkService {
    private final SnWorkEngine e;
    private final DurableTaskService tasks;
    private final CommandValidator validator;
    private final SnConvert convert;

    public SnWorkServiceImpl(
            SnWorkEngine engine,
            DurableTaskService tasks,
            CommandValidator validator,
            SnConvert convert) {
        this.e = engine;
        this.tasks = tasks;
        this.validator = validator;
        this.convert = convert;
    }

    private static String pi(String value) {
        String pi = SnPolicy.text(value);
        if (pi.isEmpty() || pi.length() > 128 || pi.codePoints().anyMatch(Character::isISOControl))
            throw SnPolicy.invalid();
        return pi;
    }

    private static Long optionalId(String value) {
        return value == null ? null : SnPolicy.id(value);
    }

    private String scope(Actor actor, String action) {
        return TaskDigests.sha256(
                actor.userId() + ":" + action + ":" + actor.securityVersion() + ":ALL");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PiInitializationVO initialization(String value) {
        e.policy.read("sn:pi-init:view", true);
        String pi = pi(value);
        String customer = e.customer(pi, "");
        var rule = e.effective(pi, customer);
        var head = rule == null ? null : e.rules.selectById(rule.getRuleId());
        return new PiInitializationVO(
                pi,
                customer,
                e.rows.inSnapshot(pi) > 0,
                convert.counter(e.counter(pi)),
                rule == null ? null : convert.version(rule),
                head == null ? "" : head.getCode(),
                head == null ? "" : head.getBindScope(),
                head == null ? "" : head.getBindValue());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<SnWorkVO> imports(String value, SnPageQuery query) {
        validator.requireValid(query);
        e.policy.read("sn:pi-init:view", true);
        String pi = pi(value);
        return new PageResult<>(
                e
                        .rows
                        .history(
                                pi,
                                null,
                                "PI_INIT",
                                (query.getPage() - 1) * query.getSize(),
                                query.getSize())
                        .stream()
                        .map(this::work)
                        .toList(),
                Long.toString(e.rows.importCount(pi)),
                query.getPage(),
                query.getSize());
    }

    @Override
    @Idempotent
    public TaskVO initialize(PiInitializationDTO command) {
        validator.requireValid(command);
        var actor = e.policy.read("sn:pi-init:execute", true);
        String pi = pi(command.pi());
        Long start = optionalId(command.start()), factory = optionalId(command.factoryId());
        List<String> sns =
                command.sns().stream().map(SnPolicy::text).filter(s -> !s.isEmpty()).toList();
        e.resource(sns.size());
        if (start == null && sns.isEmpty()) throw SnPolicy.invalid();
        Set<String> unique = new HashSet<>();
        for (String sn : sns) {
            if (sn.codePointCount(0, sn.length()) > 64
                    || sn.codePoints()
                            .anyMatch(
                                    c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF)))
                throw SnPolicy.invalid();
            if (!unique.add(sn)) throw new BusinessException(SN_CONFLICT);
        }
        String encoded = e.encode(sns);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > e.settings.getMaxImportBytes())
            throw new BusinessException(RESOURCE_LIMIT);
        String customer = SnPolicy.text(command.customer()),
                material = SnPolicy.text(command.material()),
                filename = SnPolicy.text(command.fileName());
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("v", 1);
        canonical.put("pi", pi);
        canonical.put("start", start == null ? null : start.toString());
        canonical.put("factoryId", factory == null ? null : factory.toString());
        canonical.put("customer", customer);
        canonical.put("material", material);
        canonical.put("fileName", filename);
        canonical.put("snsDigest", TaskDigests.sha256(encoded));
        return tasks.submit(
                SnWorkEngine.type("PI_INIT"),
                command.operationId(),
                e.encode(canonical),
                scope(actor, "PI_INIT"),
                task -> {
                    var fresh = e.policy.lock("sn:pi-init:execute");
                    var counter = e.lockPi(pi);
                    if (counter.startLocked()) throw new BusinessException(PI_INIT_LOCKED);
                    e.factory(factory);
                    String resolved = e.customer(pi, customer);
                    var rule = e.effective(pi, resolved);
                    if (start != null
                            && start
                                    > (rule == null
                                            ? Long.MAX_VALUE
                                            : SequenceCodec.capacity(SnWorkEngine.spec(rule))))
                        throw new BusinessException(SEQUENCE_OVERFLOW);
                    if (start != null && rule != null)
                        e.next.current(pi, counter.lastSeq(), SnWorkEngine.spec(rule))
                                .requireAboveIncoming(start);
                    var input =
                            new SnWorkInput(
                                    pi,
                                    null,
                                    factory,
                                    resolved,
                                    material,
                                    start,
                                    sns,
                                    filename,
                                    rule == null ? null : rule.getRuleId(),
                                    rule == null ? null : rule.getVersion(),
                                    rule == null ? null : rule.getContentDigest(),
                                    null,
                                    List.of());
                    e.occupy(pi, task.id());
                    insert(task, "PI_INIT", input, sns.size());
                    e.audit(fresh.userId(), "sn:pi-init:submit", task.id());
                });
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GenerationContextVO context(String id) {
        e.policy.read("sn:generation:view", true);
        var bill = e.bill(SnPolicy.id(id));
        var resolved = e.resolver.fixed(bill.getPi(), bill.getCustomer());
        var rule = resolved == null ? null : resolved.version();
        var counter = e.counter(bill.getPi());
        long generated = 0, allocated = 0;
        for (var q : e.quotaMap(bill.getId()).values()) {
            generated = Math.addExact(generated, q.generatedQty());
            allocated = Math.addExact(allocated, q.allocatedQty());
        }
        var issues = new ArrayList<>(e.issues(bill, rule));
        if (rule == null && e.rules.selectCount(null) > 0) issues.remove("RULE_MISSING");
        if (counter.activeTaskId() != null) issues.add("PI_BUSY");
        String factoryName = "";
        List<GenerationContextVO.Summary> summary = new ArrayList<>();
        if (bill.getFactoryId() != null) {
            var factory = e.factories.selectById(bill.getFactoryId());
            if (factory != null) factoryName = factory.getName();
            var siblings = e.rows.summary(bill.getPi(), bill.getFactoryId());
            if (siblings.size() > 1000) throw new BusinessException(RESOURCE_LIMIT);
            for (var sibling : siblings) {
                long gen = 0, alloc = 0;
                for (var q : e.quotaMap(sibling.getId()).values()) {
                    gen = Math.addExact(gen, q.generatedQty());
                    alloc = Math.addExact(alloc, q.allocatedQty());
                }
                summary.add(
                        new GenerationContextVO.Summary(
                                sibling.getId().toString(),
                                sibling.getBillNo(),
                                sibling.getActive(),
                                com.adtek.sn.service.support.PlannedQuantity.text(
                                        sibling.getTotalQty()),
                                Long.toString(gen),
                                Long.toString(alloc),
                                Long.toString(e.remaining(sibling))));
            }
        }
        var head = rule == null ? null : e.rules.selectById(rule.getRuleId());
        return new GenerationContextVO(
                convert.bill(
                        bill,
                        Long.toString(generated),
                        Long.toString(allocated),
                        Long.toString(e.remaining(bill))),
                factoryName,
                convert.counter(counter),
                rule == null ? null : convert.version(rule),
                Long.toString(e.rows.pendingCount(bill.getId())),
                List.copyOf(issues),
                List.copyOf(summary),
                history(bill, "GENERATE"),
                history(bill, "ALLOCATE"),
                Integer.toString(e.settings.getMaxBatchQuantity()),
                head == null ? "" : head.getCode(),
                head == null ? "" : head.getBindScope(),
                head == null ? "" : head.getBindValue(),
                resolved == null ? "NONE" : resolved.source(),
                rule == null,
                rule == null
                        ? null
                        : e.next.current(bill.getPi(), counter.lastSeq(), SnWorkEngine.spec(rule))
                                .view(SnWorkEngine.spec(rule)));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<RuleVO> ruleOptions(SnPageQuery query) {
        validator.requireValid(query);
        e.policy.read("sn:generation:view", true);
        var where =
                new QueryWrapper<RuleEntity>()
                        .orderByAsc(
                                "CASE WHEN bind_scope='GENERAL' THEN 0 ELSE 1 END", "code", "id");
        String like = SnPolicy.like(query.getQ());
        if (like != null)
            where.and(
                    w ->
                            w.apply("code LIKE {0} ESCAPE '!'", like)
                                    .or()
                                    .apply("name LIKE {0} ESCAPE '!'", like));
        var page = e.rules.selectPage(new Page<>(query.getPage(), query.getSize()), where);
        List<RuleVO> options = new ArrayList<>();
        if (!page.getRecords().isEmpty()) {
            var versions =
                    e.versions.current(page.getRecords().stream().map(RuleEntity::getId).toList());
            for (var rule : page.getRecords())
                options.add(
                        convert.rule(
                                rule,
                                convert.version(
                                        versions.stream()
                                                .filter(v -> v.getRuleId().equals(rule.getId()))
                                                .findFirst()
                                                .orElseThrow())));
        }
        return new PageResult<>(
                options, Long.toString(page.getTotal()), query.getPage(), query.getSize());
    }

    private SnWorkVO work(SnWorkEntity work) {
        var factory = work.factoryId() == null ? null : e.factories.selectById(work.factoryId());
        return convert.work(work, factory == null ? "" : factory.getCode());
    }

    private List<SnWorkVO> history(OrderBillEntity bill, String kind) {
        return e.rows.billHistory(bill.getId(), kind, 0, 20).stream().map(this::work).toList();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<SnWorkVO> history(String id, String kind, SnPageQuery query) {
        validator.requireValid(query);
        e.policy.read("sn:generation:view", true);
        if (!Set.of("GENERATE", "ALLOCATE").contains(kind)) throw SnPolicy.invalid();
        long bill = e.bill(SnPolicy.id(id)).getId();
        return new PageResult<>(
                e
                        .rows
                        .billHistory(
                                bill,
                                kind,
                                (query.getPage() - 1) * query.getSize(),
                                query.getSize())
                        .stream()
                        .map(this::work)
                        .toList(),
                Long.toString(e.rows.workCount(bill, kind)),
                query.getPage(),
                query.getSize());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GenerationPreviewVO preview(GenerationPreviewDTO command) {
        validator.requireValid(command);
        var actor = e.policy.lock("sn:generation:preview");
        e.control.lock();
        var plan =
                e.calculate(
                        SnPolicy.id(command.billId()),
                        SnPolicy.id(command.quantity()),
                        optionalId(command.start()),
                        optionalId(command.ruleId()));
        var counter = e.counter(plan.bill().getPi());
        if (counter.activeTaskId() != null) throw new BusinessException(PI_BUSY);
        String id = UUID.randomUUID().toString();
        Instant expiry = Instant.now().plusSeconds(e.settings.getPreviewSeconds());
        e.rows.preview(
                new SnPreviewEntity(
                        id,
                        actor.userId(),
                        plan.bill().getPi(),
                        plan.bill().getId(),
                        e.encode(plan),
                        expiry,
                        null,
                        Instant.now()));
        return new GenerationPreviewVO(
                id,
                Long.toString(plan.quantity()),
                Long.toString(plan.baseLast()),
                Long.toString(plan.start()),
                Long.toString(plan.end()),
                SequenceCodec.format(plan.start(), plan.spec()),
                SequenceCodec.format(plan.end(), plan.spec()),
                expiry,
                plan.segments().stream()
                        .map(
                                s ->
                                        new GenerationPreviewVO.Segment(
                                                s.entryId(),
                                                Long.toString(s.ordinal()),
                                                s.material(),
                                                Long.toString(s.quantity()),
                                                Long.toString(s.start()),
                                                Long.toString(s.end()),
                                                SequenceCodec.format(s.start(), plan.spec()),
                                                SequenceCodec.format(s.end(), plan.spec())))
                        .toList(),
                new SnNextSequence.Position(plan.baseLast(), plan.incomingMax(), plan.incomingSn())
                        .view(plan.spec()));
    }

    @Override
    @Idempotent
    public TaskVO generate(GenerationDTO command) {
        validator.requireValid(command);
        var actor = e.policy.read("sn:generation:execute", true);
        String previewId = TaskAccess.uuid(command.previewId());
        return tasks.submit(
                SnWorkEngine.type("GENERATE"),
                command.operationId(),
                e.encode(new TreeMap<>(Map.of("v", 1, "previewId", previewId))),
                scope(actor, "GENERATE"),
                task -> {
                    var fresh = e.policy.lock("sn:generation:execute");
                    e.control.lock();
                    var preview = e.rows.previewById(previewId);
                    if (preview == null || preview.actorId() != fresh.userId())
                        throw SnPolicy.missing();
                    if (preview.taskId() != null || !preview.expiresAt().isAfter(Instant.now()))
                        throw new BusinessException(PREVIEW_CHANGED);
                    var plan = e.plan(preview);
                    e.lockPi(preview.pi());
                    e.samePlan(plan);
                    e.occupy(preview.pi(), task.id());
                    if (e.rows.bind(previewId, task.id()) != 1)
                        throw new BusinessException(PREVIEW_CHANGED);
                    var input =
                            new SnWorkInput(
                                    preview.pi(),
                                    preview.billId(),
                                    plan.bill().getFactoryId(),
                                    plan.bill().getCustomer(),
                                    "",
                                    plan.requestedStart(),
                                    List.of(),
                                    "",
                                    plan.ruleId(),
                                    plan.ruleNumber(),
                                    plan.ruleDigest(),
                                    plan,
                                    List.of());
                    insert(task, "GENERATE", input, plan.quantity());
                    e.audit(fresh.userId(), "sn:generation:submit", task.id());
                });
    }

    @Override
    @Idempotent
    public TaskVO allocate(AllocationDTO command) {
        validator.requireValid(command);
        var actor = e.policy.read("sn:allocation:execute", true);
        long billId = SnPolicy.id(command.billId()),
                expected = SnPolicy.id(command.expectedQuantity());
        e.resource(expected);
        return tasks.submit(
                SnWorkEngine.type("ALLOCATE"),
                command.operationId(),
                e.encode(
                        new TreeMap<>(
                                Map.of(
                                        "v",
                                        1,
                                        "billId",
                                        Long.toString(billId),
                                        "expectedQuantity",
                                        Long.toString(expected)))),
                scope(actor, "ALLOCATE"),
                task -> {
                    var fresh = e.policy.lock("sn:allocation:execute");
                    e.control.lock();
                    var bill = e.bill(billId);
                    var counter = e.lockPi(bill.getPi());
                    if (counter.activeTaskId() != null) throw new BusinessException(PI_BUSY);
                    if (e.rows.pendingCount(billId) != expected)
                        throw new BusinessException(ALLOCATION_CHANGED);
                    var members = e.rows.pending(billId);
                    if (bill.getFactoryId() == null
                            || members.isEmpty()
                            || members.stream()
                                    .anyMatch(
                                            i ->
                                                    !Objects.equals(
                                                                    i.sourceFactoryId(),
                                                                    bill.getFactoryId())
                                                            || !Objects.equals(
                                                                    i.sourceOrgId(),
                                                                    bill.getOrgId())
                                                            || !i.sourcePi().equals(bill.getPi())))
                        throw new BusinessException(ALLOCATION_CHANGED);
                    e.factory(bill.getFactoryId());
                    var input =
                            new SnWorkInput(
                                    bill.getPi(),
                                    billId,
                                    bill.getFactoryId(),
                                    bill.getCustomer(),
                                    "",
                                    null,
                                    List.of(),
                                    "",
                                    null,
                                    null,
                                    null,
                                    null,
                                    members.stream()
                                            .map(i -> new SnWorkInput.Member(i.genMonth(), i.id()))
                                            .toList());
                    e.occupy(bill.getPi(), task.id());
                    insert(task, "ALLOCATE", input, expected);
                    e.audit(fresh.userId(), "sn:allocation:submit", task.id());
                });
    }

    private void insert(TaskEntity task, String kind, SnWorkInput input, long quantity) {
        e.rows.insert(
                new SnWorkEntity(
                        task.id(),
                        kind,
                        input.pi(),
                        input.billId(),
                        input.factoryId(),
                        task.userId(),
                        e.encode(input),
                        "PENDING",
                        quantity,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        input.fileName(),
                        null,
                        null,
                        null));
    }

    @Override
    public TaskVO operation(String kind, String id) {
        return tasks.operation(SnWorkEngine.type(kind), id);
    }
}
