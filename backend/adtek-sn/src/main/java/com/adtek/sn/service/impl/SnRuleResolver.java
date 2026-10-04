package com.adtek.sn.service.impl;

import static com.adtek.sn.constant.ErrorCodeConstants.*;

import com.adtek.common.core.BusinessException;
import com.adtek.sn.domain.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.support.SnPolicy;

import org.springframework.stereotype.Component;

import java.util.List;

/** 内部解析器；调用方负责当前权限和事务。显式绑定优先，之后沿用首次生成选择，初始化可回退通用。 */
@Component
public class SnRuleResolver {
    private final RuleMapper rules;
    private final RuleVersionMapper versions;
    private final PiRuleChoiceMapper choices;

    public SnRuleResolver(
            RuleMapper rules, RuleVersionMapper versions, PiRuleChoiceMapper choices) {
        this.rules = rules;
        this.versions = versions;
        this.choices = choices;
    }

    record Resolution(RuleEntity head, RuleVersionEntity version, String source) {}

    Resolution fixed(String pi, String customer) {
        var bound = rules.effective(pi, customer);
        if (bound != null && !"GENERAL".equals(bound.getBindScope()))
            return resolution(bound, bound.getBindScope());
        Long chosen = choices.chosen(pi);
        if (chosen == null) chosen = choices.previous(pi);
        return chosen == null ? null : resolution(require(chosen), "CHOSEN");
    }

    Resolution effective(String pi, String customer) {
        var fixed = fixed(pi, customer);
        return fixed != null ? fixed : resolution(rules.effective(pi, customer), "GENERAL");
    }

    Resolution generation(String pi, String customer, Long selected) {
        var fixed = fixed(pi, customer);
        if (fixed != null) return fixed;
        if (selected == null) throw new BusinessException(RULE_CHOICE_REQUIRED);
        return resolution(require(selected), "PICKED");
    }

    void remember(String pi, String customer, long rule) {
        if (fixed(pi, customer) == null) choices.choose(pi, rule);
    }

    private RuleEntity require(long id) {
        var rule = rules.selectById(id);
        if (rule == null) throw SnPolicy.missing();
        return rule;
    }

    private Resolution resolution(RuleEntity rule, String source) {
        return rule == null
                ? null
                : new Resolution(rule, versions.current(List.of(rule.getId())).getFirst(), source);
    }
}
