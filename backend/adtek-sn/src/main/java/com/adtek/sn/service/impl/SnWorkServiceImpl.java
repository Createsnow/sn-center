package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.annotation.Idempotent;
import com.adtek.common.core.*;
import com.adtek.framework.security.CommandValidator;
import com.adtek.framework.security.IdentityNamePort;
import com.adtek.framework.task.*;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.framework.task.service.TaskExecutionService;
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

import java.time.Instant;
import java.util.*;

@Service
public class SnWorkServiceImpl implements SnWorkService {
    private final SnWorkEngine e;
    private final DurableTaskService tasks;
    private final CommandValidator validator;
    private final SnConvert convert;
    private final IdentityNamePort names;
    private final TaskExecutionService execution;

    public SnWorkServiceImpl(
            SnWorkEngine engine,
            DurableTaskService tasks,
            CommandValidator validator,
            SnConvert convert,
            IdentityNamePort names,
            TaskExecutionService execution) {
        this.e = engine;
        this.tasks = tasks;
        this.validator = validator;
        this.convert = convert;
        this.names = names;
        this.execution = execution;
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
        List<String> sns = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < command.sns().size(); index++) {
            String sn = SnPolicy.text(command.sns().get(index));
            if (sn.isEmpty()) continue;
            if (sn.codePointCount(0, sn.length()) > 64)
                throw InitializationErrors.error(
                        "PI_IMPORT_SN_INVALID", Map.of("line", Integer.toString(index + 1)));
            if (sn.codePoints()
                    .anyMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF)))
                throw SnPolicy.invalid();
            if (!unique.add(sn))
                throw InitializationErrors.error("PI_IMPORT_DUP_IN_LIST", Map.of("sn", sn));
            sns.add(sn);
        }
        if (start == null && sns.isEmpty())
            throw InitializationErrors.error("PI_INIT_EMPTY", Map.of());
        if (sns.size() > e.settings.getMaxImportQuantity())
            throw InitializationErrors.error(
                    "PI_IMPORT_TOO_LARGE",
                    Map.of("max", Integer.toString(e.settings.getMaxImportQuantity())));
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
        canonical.put("snsDigest", SnImportDigest.of(e.json, sns));
        return tasks.submit(
                SnWorkEngine.type("PI_INIT"),
                command.operationId(),
                e.encode(canonical),
                scope(actor, "PI_INIT"),
                task -> {
                    var fresh = e.policy.lock("sn:pi-init:execute");
                    var counter = e.lockPi(pi);
                    if (counter.startLocked()) throw InitializationErrors.locked(pi);
                    e.factory(factory);
                    String resolved = e.customer(pi, customer);
                    var rule = e.effective(pi, resolved);
                    long maximum =
                            rule == null
                                    ? Long.MAX_VALUE
                                    : SequenceCodec.capacity(SnWorkEngine.spec(rule));
                    if (start != null && start > maximum) throw InitializationErrors.start(maximum);
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
                                    List.of(),
                                    filename,
                                    rule == null ? null : rule.getRuleId(),
                                    rule == null ? null : rule.getVersion(),
                                    rule == null ? null : rule.getContentDigest(),
                                    null,
                                    List.of());
                    e.occupy(pi, task.id());
                    insert(task, "PI_INIT", input, sns.size());
                    for (int i = 0; i < sns.size(); i += SnImportDigest.CHUNK_SIZE) {
                        var chunk =
                                sns.subList(i, Math.min(sns.size(), i + SnImportDigest.CHUNK_SIZE));
                        e.imports.insert(
                                new SnImportChunkEntity(
                                        task.id(),
                                        i / SnImportDigest.CHUNK_SIZE,
                                        chunk.size(),
                                        e.encode(chunk)));
                    }
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
        if (bill.getFactoryId() != null) {
            var factory = e.factories.selectById(bill.getFactoryId());
            if (factory != null) factoryName = factory.getName();
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
                summary(bill),
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

    /** 原型先列当前 PI/组织的订单，再合并首次 PI/最后生成工厂匹配的历史台账。 */
    private List<GenerationContextVO.Summary> summary(OrderBillEntity bill) {
        Map<Long, GenerationContextVO.Summary> result = new LinkedHashMap<>();
        for (var sibling : e.generation.current(bill.getPi(), bill.getOrgName())) {
            String total = PlannedQuantity.text(sibling.getTotalQty());
            result.put(
                    sibling.getId(),
                    new GenerationContextVO.Summary(
                            sibling.getId().toString(),
                            sibling.getBillNo(),
                            true,
                            total,
                            "0",
                            "0",
                            total));
        }
        if (bill.getFactoryId() != null) {
            var historical = e.generation.historical(bill.getPi(), bill.getFactoryId());
            // 一次读取历史累计，避免按每张订单单独查询；不截断原型的完整汇总。
            Map<Long, long[]> totals = new HashMap<>();
            if (!historical.isEmpty())
                for (var q :
                        e.quotas.forBills(
                                historical.stream().map(OrderBillEntity::getId).toList())) {
                    var sum = totals.computeIfAbsent(q.billId(), ignored -> new long[2]);
                    sum[0] = Math.addExact(sum[0], q.generatedQty());
                    sum[1] = Math.addExact(sum[1], q.allocatedQty());
                }
            for (var sibling : historical) {
                var current = result.get(sibling.getId());
                var sums = totals.getOrDefault(sibling.getId(), new long[2]);
                String total = current == null ? "0" : current.totalQty();
                result.put(
                        sibling.getId(),
                        new GenerationContextVO.Summary(
                                sibling.getId().toString(),
                                sibling.getBillNo(),
                                current != null,
                                total,
                                Long.toString(sums[0]),
                                Long.toString(sums[1]),
                                Long.toString(Math.max(0, Long.parseLong(total) - sums[0]))));
            }
        }
        return List.copyOf(result.values());
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
        var number = "GENERATE".equals(work.kind()) ? e.rows.generationLabel(work.taskId()) : null;
        long processed =
                "SUCCESS".equals(work.state())
                        ? work.quantity()
                        : "FAILED".equals(work.state())
                                ? 0
                                : Math.min(
                                        work.quantity(),
                                        Long.parseLong(
                                                tasks.businessProgress(work.taskId()).processed()));
        var plan = "GENERATE".equals(work.kind()) ? e.input(work).plan() : null;
        return convert.work(
                work,
                factory == null ? "" : factory.getCode(),
                number == null ? null : number.toString(),
                Long.toString(processed),
                "PI_INIT".equals(work.kind())
                        ? InitializationErrors.read(e.json, work.errorCode(), work.errorParams())
                        : GenerationErrors.read(e.json, work.errorCode(), work.errorParams()),
                work.firstSn() != null || plan == null
                        ? work.firstSn()
                        : SequenceCodec.format(plan.start(), plan.spec()),
                work.lastSn() != null || plan == null
                        ? work.lastSn()
                        : SequenceCodec.format(plan.end(), plan.spec()));
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
        var inline = new java.util.concurrent.atomic.AtomicReference<TaskEntity>();
        var submitted =
                tasks.submit(
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
                            if (preview.taskId() != null)
                                throw GenerationErrors.preview("GEN_PREVIEW_USED");
                            if (!preview.expiresAt().isAfter(Instant.now()))
                                throw GenerationErrors.preview("GEN_PREVIEW_EXPIRED");
                            var counter = e.lockPi(preview.pi());
                            var plan = e.plan(preview);
                            if (counter.lastSeq() != plan.baseLast())
                                throw GenerationErrors.stale(
                                        preview.pi(), plan.baseLast(), counter.lastSeq());
                            if (!e.bill(preview.billId()).getActive()) throw SnPolicy.missing();
                            e.occupy(preview.pi(), task.id());
                            if (e.rows.bind(previewId, task.id()) != 1)
                                throw GenerationErrors.preview("GEN_PREVIEW_USED");
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
                            // 新任务尚未提交，对同一行领取；避免后台工作器抢先令小批量随机变成异步反馈。
                            if (plan.quantity() <= e.settings.getInlineGenerationQuantity())
                                inline.set(
                                        execution.claimSubmitted(
                                                task.id(), UUID.randomUUID().toString()));
                        });
        // 提交事务先结束；同步小批量也使用相同领取、执行与失败事务，失败历史不能随 HTTP 异常回滚。
        var lease = inline.get();
        if (lease == null) return submitted;
        try {
            execution.execute(lease);
        } catch (Exception failure) {
            execution.failed(lease, failure);
            var terminal = tasks.get(submitted.taskId());
            if (terminal.state() == TaskState.FAILED
                    && failure instanceof BusinessException business) throw business;
            return terminal;
        }
        return tasks.get(submitted.taskId());
    }

    @Override
    @Idempotent
    public TaskVO allocate(AllocationDTO command) {
        validator.requireValid(command);
        var actor = e.policy.read("sn:allocation:execute", true);
        long billId = SnPolicy.id(command.billId()),
                expected = SnPolicy.id(command.expectedQuantity());
        long quantity = command.quantity() == null ? expected : SnPolicy.id(command.quantity());
        e.resource(quantity);
        if (quantity > expected)
            throw GenerationErrors.error(
                    ALLOCATION_QTY_EXCEEDED,
                    "ALLOC_QTY_EXCEEDED",
                    Map.of("qty", Long.toString(quantity), "pending", Long.toString(expected)));
        // 省略 quantity 的旧请求保持 v1 指纹，已提交的整单操作仍能按原 operationId 重放。
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("v", command.factoryCode() != null ? 3 : command.quantity() == null ? 1 : 2);
        canonical.put("billId", Long.toString(billId));
        canonical.put("expectedQuantity", Long.toString(expected));
        if (command.quantity() != null) canonical.put("quantity", Long.toString(quantity));
        if (command.factoryCode() != null)
            canonical.put("factoryCode", command.factoryCode().strip());
        return tasks.submit(
                SnWorkEngine.type("ALLOCATE"),
                command.operationId(),
                e.encode(canonical),
                scope(actor, "ALLOCATE"),
                task -> {
                    var fresh = e.policy.lock("sn:allocation:execute");
                    e.control.lock();
                    var bill = e.bill(billId);
                    var ledger = e.generation.find(billId);
                    if (ledger == null)
                        throw GenerationErrors.error(
                                ALLOCATION_CHANGED,
                                "ALLOC_NOTHING",
                                Map.of("bill_no", bill.getBillNo()));
                    // 升级前若缺少可信首次客户事实，不用今天的 ERP 表头猜补历史。
                    if (ledger.firstCustomer() == null)
                        throw new BusinessException(ALLOCATION_CHANGED);
                    String requestedFactory =
                            command.factoryCode() == null
                                    ? Objects.toString(bill.getFactoryCode(), "")
                                    : command.factoryCode().strip();
                    var factory = e.factories.selectById(ledger.factoryId());
                    if (factory == null) throw SnPolicy.missing();
                    if (!requestedFactory.isEmpty() && !requestedFactory.equals(factory.getCode()))
                        throw GenerationErrors.error(
                                ALLOCATION_CHANGED,
                                "ALLOC_FACTORY_MISMATCH",
                                Map.of("factory", factory.getCode()));
                    var counter = e.lockPi(ledger.firstPi());
                    if (counter.activeTaskId() != null) throw new BusinessException(PI_BUSY);
                    if (e.rows.pendingCount(billId) != expected)
                        throw new BusinessException(ALLOCATION_CHANGED);
                    var members = e.rows.pendingBatch(billId, quantity);
                    if (members.size() != quantity) throw new BusinessException(ALLOCATION_CHANGED);
                    e.factory(ledger.factoryId());
                    var input =
                            new SnWorkInput(
                                    ledger.firstPi(),
                                    billId,
                                    ledger.factoryId(),
                                    ledger.firstCustomer(),
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
                    e.occupy(ledger.firstPi(), task.id());
                    insert(task, "ALLOCATE", input, quantity);
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
                        null,
                        names.username(task.userId()).orElse(null),
                        null));
        // 与任务提交同事务，仅生成任务占用编号；重试不新增编号。
        if ("GENERATE".equals(kind)) e.rows.generationNumber(task.id());
    }

    @Override
    public TaskVO operation(String kind, String id) {
        return tasks.operation(SnWorkEngine.type(kind), id);
    }
}
