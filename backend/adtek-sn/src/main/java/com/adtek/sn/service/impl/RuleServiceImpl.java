package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.annotation.*;
import com.adtek.common.core.*;
import com.adtek.framework.audit.AuditContext;
import com.adtek.framework.security.CommandValidator;
import com.adtek.framework.task.TaskDigests;
import com.adtek.sn.convert.SnConvert;
import com.adtek.sn.domain.*;
import com.adtek.sn.dto.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.service.RuleService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
public class RuleServiceImpl implements RuleService {
    private final OrderBillMapper bills;
    private final SnRuleResolver resolver;
    private final RuleMapper rules;
    private final RuleVersionMapper versions;
    private final SnPolicy policy;
    private final CommandValidator validator;
    private final SnConvert convert;

    public RuleServiceImpl(
            RuleMapper rules,
            RuleVersionMapper versions,
            SnPolicy policy,
            CommandValidator validator,
            SnConvert convert,
            OrderBillMapper bills,
            SnRuleResolver resolver) {
        this.resolver = resolver;
        this.bills = bills;
        this.rules = rules;
        this.versions = versions;
        this.policy = policy;
        this.validator = validator;
        this.convert = convert;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RuleVO> list(SnPageQuery query) {
        validator.requireValid(query);
        policy.read("sn:rule:list", true);
        String scope = SnPolicy.optional(query.getScope());
        if (scope != null && !Set.of("GENERAL", "CUSTOMER", "PI").contains(scope))
            throw SnPolicy.invalid();
        var where =
                Wrappers.<RuleEntity>lambdaQuery()
                        .eq(scope != null, RuleEntity::getBindScope, scope)
                        .orderByAsc(
                                RuleEntity::getBindScope,
                                RuleEntity::getBindValue,
                                RuleEntity::getId);
        String like = SnPolicy.like(query.getQ());
        if (like != null)
            where.and(
                    w ->
                            w.apply("code LIKE {0} ESCAPE '!'", like)
                                    .or()
                                    .apply("name LIKE {0} ESCAPE '!'", like)
                                    .or()
                                    .apply("bind_value LIKE {0} ESCAPE '!'", like));
        var page = rules.selectPage(new Page<>(query.getPage(), query.getSize()), where);
        if (page.getRecords().isEmpty())
            return new PageResult<>(
                    List.of(), Long.toString(page.getTotal()), query.getPage(), query.getSize());
        var current = versions.current(page.getRecords().stream().map(RuleEntity::getId).toList());
        var result =
                page.getRecords().stream()
                        .map(
                                r ->
                                        convert.rule(
                                                r,
                                                convert.version(
                                                        current.stream()
                                                                .filter(
                                                                        v ->
                                                                                v.getRuleId()
                                                                                                .equals(
                                                                                                        r
                                                                                                                .getId())
                                                                                        && v.getVersion()
                                                                                                .equals(
                                                                                                        r
                                                                                                                .getCurrentVersion()))
                                                                .findFirst()
                                                                .orElseThrow())))
                        .toList();
        return new PageResult<>(
                result, Long.toString(page.getTotal()), query.getPage(), query.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RuleVersionVO> versions(String id, SnPageQuery query) {
        validator.requireValid(query);
        policy.read("sn:rule:list", true);
        long ruleId = SnPolicy.id(id);
        if (rules.selectById(ruleId) == null) throw SnPolicy.missing();
        var page =
                versions.selectPage(
                        new Page<>(query.getPage(), query.getSize()),
                        Wrappers.<RuleVersionEntity>lambdaQuery()
                                .eq(RuleVersionEntity::getRuleId, ruleId)
                                .orderByDesc(RuleVersionEntity::getVersion));
        return new PageResult<>(
                page.getRecords().stream().map(convert::version).toList(),
                Long.toString(page.getTotal()),
                query.getPage(),
                query.getSize());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @OperLog(
            title = "编码规则",
            action = "sn:rule:create",
            businessType = OperLog.BusinessType.INSERT,
            fields = {"name", "digest", "currentVersion"})
    public RuleVO create(RuleSaveDTO input) {
        validator.requireValid(input);
        var command = normalize(input);
        var spec =
                spec(
                        command.prefix(),
                        command.suffix(),
                        command.base(),
                        command.length(),
                        command.alphabet());
        var actor = policy.lock("sn:rule:create");
        var rule = convert.rule(command);
        rule.setCurrentVersion(1L);
        rule.setRevision(1L);
        rule.setCreatedBy(actor.userId());
        rule.setCreatedAt(Instant.now());
        rule.setUpdatedAt(rule.getCreatedAt());
        try {
            rules.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(RULE_EXISTS);
        }
        var version = version(rule, 1, spec, actor.userId());
        versions.insert(version);
        AuditContext.record(rule.getId().toString(), Map.of(), snapshot(rule, version));
        return convert.rule(rule, convert.version(version));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @OperLog(
            title = "编码规则",
            action = "sn:rule:update",
            businessType = OperLog.BusinessType.UPDATE,
            fields = {"name", "digest", "currentVersion"})
    public RuleChangeVO update(String id, RuleSaveDTO input) {
        validator.requireValid(input);
        var command = normalize(input);
        var spec =
                spec(
                        command.prefix(),
                        command.suffix(),
                        command.base(),
                        command.length(),
                        command.alphabet());
        var actor = policy.lock("sn:rule:update");
        var rule = rules.lock(SnPolicy.id(id));
        if (rule == null) throw SnPolicy.missing();
        if (rule.getRevision() != SnPolicy.id(command.revision()))
            throw new BusinessException(VERSION_CHANGED);
        if (!rule.getCode().equals(command.code())
                || !rule.getBindScope().equals(command.bindScope())
                || !rule.getBindValue().equals(command.bindValue())) throw SnPolicy.invalid();
        var current = versions.lock(rule.getId(), rule.getCurrentVersion());
        var before = snapshot(rule, current);
        boolean changed = !current.getContentDigest().equals(digest(spec));
        boolean renamed = !rule.getName().equals(command.name());
        String action = "UNCHANGED";
        if (changed) {
            if (current.getUsed()) {
                current = version(rule, rule.getCurrentVersion() + 1, spec, actor.userId());
                versions.insert(current);
                rule.setCurrentVersion(current.getVersion());
                action = "NEW_VERSION";
            } else {
                apply(current, spec);
                current.setUpdatedAt(Instant.now());
                versions.updateById(current);
                action = "UPDATED";
            }
        }
        if (renamed && action.equals("UNCHANGED")) action = "RENAMED";
        if (changed || renamed) {
            rule.setName(command.name());
            rule.setRevision(rule.getRevision() + 1);
            rule.setUpdatedAt(Instant.now());
            rules.updateById(rule);
        }
        AuditContext.record(id, before, snapshot(rule, current));
        return new RuleChangeVO(action, convert.rule(rule, convert.version(current)));
    }

    @Override
    public RulePreviewVO preview(RulePreviewDTO command) {
        validator.requireValid(command);
        policy.read("sn:rule:preview", true);
        var spec =
                spec(
                        command.prefix(),
                        command.suffix(),
                        command.base(),
                        command.length(),
                        command.alphabet());
        long start = 1;
        try {
            if (command.start() != null) {
                if (!command.start().matches("0|[1-9][0-9]{0,18}")) throw SnPolicy.invalid();
                start = Long.parseLong(command.start());
            }
        } catch (NumberFormatException e) {
            throw SnPolicy.invalid();
        }
        long max = SequenceCodec.capacity(spec);
        if (start > max) throw new BusinessException(SEQUENCE_OVERFLOW);
        List<RulePreviewVO.Sample> samples = new ArrayList<>();
        for (long i = start; i <= max && i - start < 5; i++) {
            samples.add(sample(i, spec));
            if (i == Long.MAX_VALUE) break;
        }
        if (max - start > 5) samples.add(sample(max, spec));
        return new RulePreviewVO(Long.toString(max), List.copyOf(samples));
    }

    @Override
    @Transactional(readOnly = true)
    public RuleVersionVO effective(String pi, String customer) {
        policy.read("sn:rule:effective", true);
        String p = SnPolicy.text(pi);
        if (p.isEmpty() || p.length() > 128 || SnPolicy.text(customer).length() > 64)
            throw SnPolicy.invalid();
        String resolved = SnPolicy.text(customer);
        if (resolved.isEmpty()) resolved = SnPolicy.text(bills.effectiveCustomer(p));
        var rule = resolver.effective(p, resolved);
        return rule == null ? null : convert.version(rule.version());
    }

    private RuleSaveDTO normalize(RuleSaveDTO input) {
        String scope = SnPolicy.text(input.bindScope());
        if (!Set.of("GENERAL", "CUSTOMER", "PI").contains(scope)) throw SnPolicy.invalid();
        String value = scope.equals("GENERAL") ? "" : SnPolicy.text(input.bindValue());
        if (!scope.equals("GENERAL") && value.isEmpty()) throw SnPolicy.invalid();
        var out =
                new RuleSaveDTO(
                        SnPolicy.text(input.code()),
                        SnPolicy.text(input.name()),
                        scope,
                        value,
                        input.prefix(),
                        input.suffix(),
                        input.base(),
                        input.length(),
                        input.alphabet(),
                        input.revision());
        validator.requireValid(out);
        return out;
    }

    private static SequenceSpec spec(
            String prefix, String suffix, Integer base, Integer length, String alphabet) {
        if (base == null || length == null) throw SnPolicy.invalid();
        String chars = SnPolicy.text(alphabet);
        return chars.isEmpty()
                ? SequenceSpec.standard(SnPolicy.text(prefix), SnPolicy.text(suffix), base, length)
                : new SequenceSpec(
                        SnPolicy.text(prefix), SnPolicy.text(suffix), base, length, chars);
    }

    private static String digest(SequenceSpec spec) {
        return TaskDigests.sha256(
                spec.prefix().length()
                        + ":"
                        + spec.prefix()
                        + spec.suffix().length()
                        + ":"
                        + spec.suffix()
                        + ":"
                        + spec.base()
                        + ":"
                        + spec.length()
                        + ":"
                        + spec.alphabet());
    }

    private RuleVersionEntity version(RuleEntity rule, long number, SequenceSpec spec, long actor) {
        var value = new RuleVersionEntity();
        value.setRuleId(rule.getId());
        value.setVersion(number);
        value.setUsed(false);
        value.setCreatedBy(actor);
        value.setCreatedAt(Instant.now());
        value.setUpdatedAt(value.getCreatedAt());
        apply(value, spec);
        return value;
    }

    private static void apply(RuleVersionEntity value, SequenceSpec spec) {
        value.setPrefix(spec.prefix());
        value.setSuffix(spec.suffix());
        value.setBase(spec.base());
        value.setSeqLength(spec.length());
        value.setAlphabet(spec.alphabet());
        value.setContentDigest(digest(spec));
    }

    private static RulePreviewVO.Sample sample(long sequence, SequenceSpec spec) {
        String sn = SequenceCodec.format(sequence, spec);
        return new RulePreviewVO.Sample(
                Long.toString(sequence),
                sn.substring(spec.prefix().length(), sn.length() - spec.suffix().length()),
                sn);
    }

    private static Map<String, Object> snapshot(RuleEntity rule, RuleVersionEntity version) {
        return Map.of(
                "name",
                rule.getName(),
                "digest",
                version.getContentDigest(),
                "currentVersion",
                rule.getCurrentVersion().toString());
    }
}
