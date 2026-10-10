package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.core.*;
import com.adtek.framework.audit.ManagementAuditPort;
import com.adtek.framework.security.AuthorizationMutationPort;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.framework.task.service.TaskProgressService;
import com.adtek.sn.domain.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.support.*;
import com.adtek.sn.service.support.SnWorkInput.*;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

/** 持久任务的事务内执行器，不另开业务事务；任务代次由framework持锁后调用，成功审计失败会回滚整批。 */
@Component
public class SnWorkEngine {
    final SnWorkMapper rows;
    final SnImportMapper imports;
    final SnRuleResolver resolver;
    final SnNextSequence next;
    final RuleMapper rules;
    final RuleVersionMapper versions;
    final OrderBillMapper bills;
    final FactoryMapper factories;
    final SourceQuotaMapper quotas;
    final BillGenerationMapper generation;
    final SnControlMapper control;
    final SnPolicy policy;
    final ObjectMapper json;
    final SnWorkSettings settings;
    private final AuthorizationMutationPort authorization;
    private final ManagementAuditPort audit;
    private final TaskProgressService progress;

    public SnWorkEngine(
            SnWorkMapper rows,
            SnImportMapper imports,
            SnRuleResolver resolver,
            SnNextSequence next,
            RuleMapper rules,
            RuleVersionMapper versions,
            OrderBillMapper bills,
            FactoryMapper factories,
            SourceQuotaMapper quotas,
            BillGenerationMapper generation,
            SnControlMapper control,
            SnPolicy policy,
            ObjectMapper json,
            SnWorkSettings settings,
            AuthorizationMutationPort authorization,
            ManagementAuditPort audit,
            TaskProgressService progress) {
        this.rows = rows;
        this.imports = imports;
        this.resolver = resolver;
        this.next = next;
        this.rules = rules;
        this.versions = versions;
        this.bills = bills;
        this.factories = factories;
        this.quotas = quotas;
        this.generation = generation;
        this.control = control;
        this.policy = policy;
        this.json = json;
        this.settings = settings;
        this.authorization = authorization;
        this.audit = audit;
        this.progress = progress;
    }

    static String permission(String kind) {
        return switch (kind) {
            case "PI_INIT" -> "sn:pi-init:execute";
            case "GENERATE" -> "sn:generation:execute";
            case "ALLOCATE" -> "sn:allocation:execute";
            default -> throw SnPolicy.invalid();
        };
    }

    static String type(String kind) {
        permission(kind);
        return "SN_" + kind;
    }

    String encode(Object value) {
        return json.writeValueAsString(value);
    }

    SnWorkInput input(SnWorkEntity row) {
        return json.readValue(row.payload(), SnWorkInput.class);
    }

    Plan plan(SnPreviewEntity row) {
        return json.readValue(row.payload(), Plan.class);
    }

    PiCounterEntity counter(String pi) {
        var value = rows.counter(pi);
        return value == null
                ? new PiCounterEntity(pi, 0, 0, 0, null, false, null, null, null)
                : value;
    }

    RuleVersionEntity effective(String pi, String customer) {
        var resolved = resolver.effective(pi, customer);
        return resolved == null ? null : resolved.version();
    }

    static SequenceSpec spec(RuleVersionEntity rule) {
        return new SequenceSpec(
                rule.getPrefix(),
                rule.getSuffix(),
                rule.getBase(),
                rule.getSeqLength(),
                rule.getAlphabet());
    }

    String customer(String pi, String explicit) {
        return explicit.isEmpty() ? SnPolicy.text(bills.effectiveCustomer(pi)) : explicit;
    }

    void factory(Long id) {
        if (id != null) {
            var value = factories.selectById(id);
            if (value == null || !value.getEnabled()) throw SnPolicy.missing();
        }
    }

    void resource(long qty) {
        if (qty > settings.getMaxBatchQuantity()) throw new BusinessException(RESOURCE_LIMIT);
    }

    void authorize(Actor actor, String kind) {
        policy.require(actor, permission(kind), true);
    }

    PiCounterEntity lockPi(String pi) {
        control.lock();
        rows.ensure(pi);
        return rows.lock(pi);
    }

    void occupy(String pi, String task) {
        if (rows.occupy(pi, task) != 1) throw new BusinessException(PI_BUSY);
    }

    void audit(long actor, String action, String id) {
        audit.success(
                new ManagementAuditPort.Event(
                        actor,
                        "SN初始化生成分配",
                        action,
                        "OTHER",
                        id,
                        Map.of(),
                        org.slf4j.MDC.get("traceId")));
    }

    List<String> issues(OrderBillEntity bill, RuleVersionEntity rule) {
        List<String> out = new ArrayList<>();
        if (!bill.getActive()) out.add("NOT_IN_SNAPSHOT");
        if (bill.getCustomer().isEmpty()) out.add("NO_CUSTOMER");
        if (bill.getPi().isEmpty()) out.add("NO_PI");
        if (bill.getFactoryId() == null) out.add("ORG_UNMAPPED");
        if (rule == null) out.add("RULE_MISSING");
        if (remaining(bill) == 0) out.add("QUOTA_EMPTY");
        return List.copyOf(out);
    }

    Map<String, SourceQuotaMapper.Quota> quotaMap(long bill) {
        Map<String, SourceQuotaMapper.Quota> map = new LinkedHashMap<>();
        for (var q : quotas.forBills(List.of(bill))) map.put(q.sourceEntryId(), q);
        return map;
    }

    long remaining(OrderBillEntity bill) {
        if (!bill.getActive() || !Boolean.TRUE.equals(bill.getQuantityReady())) return 0;
        return Math.max(0, PlannedQuantity.snCount(bill.getTotalQty()) - generated(bill.getId()));
    }

    /** 额度按整单历史累计扣减；ERP 删除、替换或缩减分录不能重新释放已发号码的额度。 */
    private long generated(long billId) {
        long generated = 0;
        for (var quota : quotaMap(billId).values())
            generated = Math.addExact(generated, quota.generatedQty());
        return generated;
    }

    OrderBillEntity bill(long id) {
        var bill = rows.bill(id);
        if (bill == null) throw SnPolicy.missing();
        return bill;
    }

    Plan calculate(long billId, long quantity, Long start, Long selectedRule) {
        resource(quantity);
        var bill = bill(billId);
        var rule = resolver.generation(bill.getPi(), bill.getCustomer(), selectedRule).version();
        var problems = issues(bill, rule);
        if (problems.contains("QUOTA_EMPTY") && problems.size() == 1)
            throw GenerationErrors.error(
                    GENERATION_NOT_READY, "GEN_QUOTA_EMPTY", Map.of("bill_no", bill.getBillNo()));
        if (!problems.isEmpty()) throw new BusinessException(GENERATION_NOT_READY);
        if (quantity > remaining(bill)) throw quotaExceeded(quantity, remaining(bill));
        factory(bill.getFactoryId());
        var counter = counter(bill.getPi());
        if (start != null && counter.startLocked()) throw new BusinessException(PI_INIT_LOCKED);
        var spec = spec(rule);
        var position = next.current(bill.getPi(), counter.lastSeq(), spec);
        if (start != null) position.requireAboveIncoming(start);
        long first = start == null ? SequenceCodec.lastSequence(position.base(), 1, spec) : start;
        long end = SequenceCodec.lastSequence(first - 1, quantity, spec);
        long todo = quantity, next = first;
        var history = quotaMap(billId);
        long skip = generated(billId);
        List<Segment> segments = new ArrayList<>();
        for (var line : rows.lines(billId)) {
            if (todo == 0) break;
            var q = history.get(line.getSourceEntryId());
            if (q == null) throw new IllegalStateException("Missing source quota");
            // 原型在当前分录序列上跳过整单已生成量；稳定来源台账只保留历史追溯，不决定本行余量。
            long available = PlannedQuantity.snCount(line.getQty());
            long skipped = Math.min(skip, available);
            skip -= skipped;
            long take = Math.min(todo, available - skipped);
            if (take > 0) {
                segments.add(
                        new Segment(
                                line.getSourceEntryId(),
                                line.getOrdinal(),
                                line.getMaterial(),
                                take,
                                next,
                                Math.addExact(next, take - 1),
                                line.getSnapshotVersion(),
                                q.generatedQty(),
                                line.getMaterialName()));
                todo -= take;
                next += take;
            }
        }
        if (todo != 0) throw quotaExceeded(quantity, remaining(bill));
        return new Plan(
                bill,
                List.copyOf(segments),
                counter.lastSeq(),
                counter.startLocked(),
                start,
                quantity,
                first,
                end,
                rule.getRuleId(),
                rule.getVersion(),
                rule.getId(),
                rule.getContentDigest(),
                spec,
                position.incomingMax(),
                position.incomingSn());
    }

    /** 只比较预演承诺的业务结果；同步时间、内部版本与来源分录内码不影响相同号段。 */
    Plan samePlan(Plan expected) {
        var current = bill(expected.bill().getId());
        if (!current.getActive()) throw SnPolicy.missing();
        // 已接受的执行先检查整单剩余额度，额度归零也保留原型的“超过剩余量”失败原因。
        if (expected.quantity() > remaining(current))
            throw quotaExceeded(expected.quantity(), remaining(current));
        var actual =
                calculate(
                        expected.bill().getId(),
                        expected.quantity(),
                        expected.requestedStart(),
                        expected.ruleId());
        if (!condition(actual).equals(condition(expected)))
            throw GenerationErrors.preview("GEN_PREVIEW_CHANGED");
        // 来源台账与客户使用锁内重算值；不能通过校验后仍写入已失效的 ERP 分录身份。
        return actual;
    }

    private BusinessException quotaExceeded(long quantity, long quota) {
        return GenerationErrors.error(
                QUOTA_EXCEEDED,
                "GEN_QUOTA_EXCEEDED",
                Map.of("qty", Long.toString(quantity), "quota", Long.toString(quota)));
    }

    private PreviewCondition condition(Plan p) {
        return new PreviewCondition(
                p.bill().getBillNo(),
                p.bill().getPi(),
                p.bill().getFactoryId(),
                p.ruleVersionId(),
                p.baseLast(),
                p.requestedStart(),
                p.start(),
                p.end(),
                p.segments().stream()
                        .map(
                                s ->
                                        new PreviewSegment(
                                                s.ordinal(),
                                                s.material(),
                                                s.materialName(),
                                                s.quantity(),
                                                s.start(),
                                                s.end(),
                                                SequenceCodec.format(s.start(), p.spec()),
                                                SequenceCodec.format(s.end(), p.spec())))
                        .toList());
    }

    private record PreviewCondition(
            String billNo,
            String pi,
            Long factoryId,
            long ruleVersionId,
            long baseLast,
            Long requestedStart,
            long start,
            long end,
            List<PreviewSegment> segments) {}

    private record PreviewSegment(
            long ordinal,
            String material,
            String materialName,
            long quantity,
            long start,
            long end,
            String firstSn,
            String lastSn) {}

    RuleVersionEntity lockRule(Long id, Long number, String digest) {
        if (id == null) return null;
        var version = versions.lock(id, number);
        if (version == null || !version.getContentDigest().equals(digest))
            throw new BusinessException(PREVIEW_CHANGED);
        return version;
    }

    void used(RuleVersionEntity rule) {
        if (rule != null && !rule.getUsed()) {
            rule.setUsed(true);
            rule.setUpdatedAt(Instant.now());
            versions.updateById(rule);
        }
    }

    public String execute(TaskEntity task, Actor requested) {
        var work = rows.find(task.id());
        if (work == null || !work.state().equals("PENDING"))
            throw new IllegalStateException("Missing pending SN work");
        var actor = authorization.lockCurrent(requested);
        authorize(actor, work.kind());
        var input = input(work);
        var counter = lockPi(work.pi());
        if (!task.id().equals(counter.activeTaskId())) throw new BusinessException(PI_BUSY);
        try {
            switch (work.kind()) {
                case "PI_INIT" -> initialize(task, actor, input, counter, work.quantity());
                case "GENERATE" -> generate(task, actor, input, counter);
                case "ALLOCATE" -> allocate(task, actor, input, counter);
                default -> throw new IllegalStateException("Unknown SN work");
            }
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(SN_CONFLICT);
        }
        if (rows.release(work.pi(), task.id()) != 1)
            throw new IllegalStateException("Lost PI task ownership");
        audit(actor.userId(), permission(work.kind()), task.id());
        return task.id();
    }

    private void initialize(
            TaskEntity task,
            Actor actor,
            SnWorkInput input,
            PiCounterEntity counter,
            long quantity) {
        if (counter.startLocked()) throw InitializationErrors.locked(input.pi());
        factory(input.factoryId());
        var current = effective(input.pi(), input.customer());
        if (!Objects.equals(input.ruleId(), current == null ? null : current.getRuleId())
                || !Objects.equals(
                        input.ruleNumber(), current == null ? null : current.getVersion())
                || !Objects.equals(
                        input.ruleDigest(), current == null ? null : current.getContentDigest()))
            throw new BusinessException(PREVIEW_CHANGED);
        var rule = lockRule(input.ruleId(), input.ruleNumber(), input.ruleDigest());
        long maximum = rule == null ? Long.MAX_VALUE : SequenceCodec.capacity(spec(rule));
        if (input.requestedStart() != null && input.requestedStart() > maximum)
            throw InitializationErrors.start(maximum);
        if (input.requestedStart() != null && rule != null)
            next.current(input.pi(), counter.lastSeq(), spec(rule))
                    .requireAboveIncoming(input.requestedStart());
        long count = 0, last = counter.lastSeq();
        Long max = null;
        if (input.requestedStart() != null) last = Math.max(last, input.requestedStart() - 1);
        boolean inline = !input.sns().isEmpty();
        if (inline && input.sns().size() != quantity)
            throw new IllegalStateException("Invalid legacy import input");
        var digest = new SnImportDigest(json);
        var ruleSpec = rule == null ? null : spec(rule);
        long processed = 0;
        int ordinal = 0;
        String first = null, finalSn = null;
        while (processed < quantity) {
            List<String> sns;
            if (inline)
                sns =
                        input.sns()
                                .subList(
                                        (int) processed,
                                        (int)
                                                Math.min(
                                                        quantity,
                                                        processed + SnImportDigest.CHUNK_SIZE));
            else {
                var chunk = imports.next(task.id(), ordinal);
                if (chunk == null || chunk.ordinal() != ordinal)
                    throw new IllegalStateException("Missing import chunk");
                sns = Arrays.asList(json.readValue(chunk.sns(), String[].class));
                if (sns.size() != chunk.quantity())
                    throw new IllegalStateException("Invalid import chunk quantity");
            }
            if (sns.isEmpty()
                    || sns.size() > SnImportDigest.CHUNK_SIZE
                    || processed + sns.size() > quantity)
                throw new IllegalStateException("Invalid import input quantity");
            digest.add(sns);
            List<SnItemEntity> batch = new ArrayList<>(sns.size());
            for (String sn : sns) {
                var parsed =
                        ruleSpec == null
                                ? java.util.OptionalLong.empty()
                                : SequenceCodec.parse(sn, ruleSpec);
                Long seq = parsed.isPresent() ? parsed.getAsLong() : null;
                if (seq != null) {
                    count++;
                    max = max == null ? seq : Math.max(max, seq);
                    last = Math.max(last, seq);
                }
                if (first == null) first = sn;
                finalSn = sn;
                batch.add(
                        item(
                                task,
                                input.pi(),
                                sn,
                                seq,
                                input.customer(),
                                input.material(),
                                input.factoryId(),
                                "IMPORT",
                                seq == null ? null : rule.getId(),
                                null,
                                null));
            }
            processed += sns.size();
            writeImport(task, input.pi(), batch, processed);
            ordinal++;
        }
        if ((!inline && imports.next(task.id(), ordinal) != null)
                || !digest.finish()
                        .equals(json.readTree(task.inputJson()).path("snsDigest").asString()))
            throw new IllegalStateException("Import input digest mismatch");
        if (count > 0) used(rule);
        rows.advance(input.pi(), last, 0, quantity, input.requestedStart(), actor.userId());
        complete(task, input.requestedStart(), null, count, max, last, first, finalSn);
    }

    private void generate(
            TaskEntity task, Actor actor, SnWorkInput input, PiCounterEntity counter) {
        var preview = rows.previewForTask(task.id());
        // 过期只在提交前拒绝；已接受的任务等待工作器期间不重新消耗预演有效期。
        if (preview == null || preview.actorId() != actor.userId())
            throw GenerationErrors.preview("GEN_PREVIEW_REQUIRED");
        if (counter.lastSeq() != input.plan().baseLast())
            throw GenerationErrors.stale(input.pi(), input.plan().baseLast(), counter.lastSeq());
        rows.lockQuota(input.plan().bill().getId());
        var p = samePlan(input.plan());
        var rule = lockRule(p.ruleId(), p.ruleNumber(), p.ruleDigest());
        long processed = 0;
        List<SnItemEntity> batch = new ArrayList<>();
        for (var s : p.segments()) {
            for (long seq = s.start(); seq <= s.end(); seq++) {
                batch.add(
                        item(
                                task,
                                input.pi(),
                                SequenceCodec.format(seq, p.spec()),
                                seq,
                                p.bill().getCustomer(),
                                s.material(),
                                null,
                                "GEN",
                                rule.getId(),
                                p.bill(),
                                s.entryId()));
                processed++;
                if (batch.size() == 500) {
                    write(task, batch, processed);
                    batch.clear();
                }
            }
            if (rows.quota(p.bill().getId(), s.entryId(), s.quantity(), 0) != 1)
                throw new IllegalStateException("Missing source quota");
        }
        if (!batch.isEmpty()) write(task, batch, processed);
        // 与 SN 和额度同事务提交；后续 ERP 改 PI/客户只影响新成员，不重写首次历史归属。
        generation.generated(
                p.bill().getId(),
                input.pi(),
                p.bill().getCustomer(),
                p.bill().getFactoryId(),
                task.id());
        used(rule);
        resolver.remember(input.pi(), p.bill().getCustomer(), p.ruleId());
        rows.advance(input.pi(), p.end(), p.quantity(), 0, p.requestedStart(), actor.userId());
        complete(
                task,
                p.start(),
                p.end(),
                null,
                null,
                p.end(),
                SequenceCodec.format(p.start(), p.spec()),
                SequenceCodec.format(p.end(), p.spec()));
    }

    private void allocate(
            TaskEntity task, Actor actor, SnWorkInput input, PiCounterEntity counter) {
        rows.lockQuota(input.billId());
        factory(input.factoryId());
        // 只重新核对提交时固定的最早一批；其余待分配成员不能被本任务扩大纳入。
        var pending = rows.pendingBatch(input.billId(), input.members().size());
        if (!pending.stream()
                .map(i -> new Member(i.genMonth(), i.id()))
                .toList()
                .equals(input.members())) throw new BusinessException(ALLOCATION_CHANGED);
        var ledger = generation.find(input.billId());
        if (ledger == null
                || ledger.factoryId() != input.factoryId()
                || !ledger.firstPi().equals(input.pi())
                || !Objects.equals(ledger.firstCustomer(), input.customer()))
            throw new BusinessException(ALLOCATION_CHANGED);
        Map<String, Long> quantities = new TreeMap<>();
        for (int offset = 0; offset < pending.size(); offset += 500) {
            var batch = pending.subList(offset, Math.min(pending.size(), offset + 500));
            if (rows.allocate(task.id(), input.factoryId(), batch) != batch.size())
                throw new BusinessException(ALLOCATION_CHANGED);
            for (var row : batch) quantities.merge(row.sourceEntryId(), 1L, Math::addExact);
            progress.record(task.id(), task.executionEpoch(), offset + batch.size(), "WRITING");
        }
        quantities.forEach(
                (entry, qty) -> {
                    if (rows.quota(input.billId(), entry, 0, qty) != 1)
                        throw new IllegalStateException("Missing allocation quota");
                });
        complete(
                task,
                pending.getFirst().seqDec(),
                pending.getLast().seqDec(),
                null,
                null,
                counter.lastSeq(),
                pending.getFirst().sn(),
                pending.getLast().sn());
    }

    private SnItemEntity item(
            TaskEntity task,
            String pi,
            String sn,
            Long sequence,
            String customer,
            String material,
            Long factory,
            String source,
            Long version,
            OrderBillEntity bill,
            String entry) {
        return new SnItemEntity(
                LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1),
                UUID.randomUUID().toString(),
                sn,
                pi,
                sequence == null ? null : pi,
                sequence,
                customer,
                material,
                factory,
                source,
                (source.equals("IMPORT")
                                ? com.adtek.sn.constant.SnStatus.PRINTED
                                : com.adtek.sn.constant.SnStatus.PENDING_ALLOC)
                        .name(),
                version,
                bill == null ? null : bill.getSourceSystem(),
                bill == null ? null : bill.getSourceAccount(),
                bill == null ? null : bill.getSourceBillId(),
                entry,
                bill == null ? null : bill.getId(),
                bill == null ? "" : bill.getBillNo(),
                pi,
                bill == null ? null : bill.getOrgId(),
                bill == null ? factory : bill.getFactoryId(),
                task.id());
    }

    private void write(TaskEntity task, List<SnItemEntity> batch, long processed) {
        rows.items(batch);
        rows.keys(batch);
        progress.record(task.id(), task.executionEpoch(), processed, "WRITING");
    }

    /** 与原型保持每千条先查完整 SN、再查来源流水；后块失败回滚此前所有业务写入。 */
    private void writeImport(TaskEntity task, String pi, List<SnItemEntity> batch, long processed) {
        String sn = rows.importedSnConflict(pi, batch.stream().map(SnItemEntity::sn).toList());
        if (sn != null)
            throw InitializationErrors.error("PI_IMPORT_DUP_EXISTING", Map.of("sn", sn, "pi", pi));
        var seqs = batch.stream().map(SnItemEntity::seqDec).filter(Objects::nonNull).toList();
        Long seq = seqs.isEmpty() ? null : rows.importedSequenceConflict(pi, seqs);
        if (seq != null)
            throw InitializationErrors.error(
                    "PI_IMPORT_SEQ_DUP", Map.of("seq", seq.toString(), "pi", pi));
        write(task, batch, processed);
    }

    private void complete(
            TaskEntity task,
            Long start,
            Long end,
            Long decoded,
            Long max,
            Long last,
            String first,
            String lastSn) {
        if (rows.success(task.id(), start, end, decoded, max, last, first, lastSn) != 1)
            throw new IllegalStateException("Lost SN result");
    }

    public void ended(TaskEntity task, String state, Integer code) {
        ended(task, state, code, Map.of());
    }

    public void ended(TaskEntity task, String state, Integer code, Map<String, String> params) {
        var work = rows.find(task.id());
        if (work == null) return;
        // 终态事实与PI占用释放加入framework的终态事务；独立失败审计不可使占用永久残留。
        control.lock();
        rows.lock(work.pi());
        rows.ended(
                task.id(),
                state,
                code,
                encode(
                        "PI_INIT".equals(work.kind())
                                ? InitializationErrors.safe(code, params)
                                : GenerationErrors.safe(code, params)));
        rows.release(work.pi(), task.id());
        if (state.equals("CANCELLED"))
            audit(task.userId(), permission(work.kind()) + ":cancel", task.id());
        else
            try {
                audit.failure(
                        new ManagementAuditPort.Event(
                                task.userId(),
                                "SN初始化生成分配",
                                permission(work.kind()) + ":failed",
                                "OTHER",
                                task.id(),
                                Map.of("errorCode", Integer.toString(code)),
                                org.slf4j.MDC.get("traceId")));
            } catch (RuntimeException unavailable) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn(
                                "SN terminal failure audit unavailable; durable task retains"
                                        + " controlled error");
            }
    }
}
