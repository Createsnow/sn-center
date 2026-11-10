package com.adtek.sn.mapper;

import com.adtek.sn.domain.RuleEntity;
import com.adtek.sn.domain.RuleVersionEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** SN 规则（sn_rule）、规则版本（sn_rule_version）、PI 绑定（sn_pi_rule）与物料行规则（sn_line_rule）。 */
@Mapper
public interface RuleMapper {
    List<Map<String, Object>> prefixRules(
            @Param("prefix") String prefix, @Param("exclude") Long exclude);

    List<Map<String, Object>> prefixRulesLock(
            @Param("prefix") String prefix, @Param("exclude") Long exclude);

    List<Map<String, Object>> overlapping(
            @Param("like") String like,
            @Param("prefix") String prefix,
            @Param("heads") List<String> heads);

    List<Map<String, Object>> versionsOfPrefixPi(
            @Param("prefix") String prefix, @Param("pi") String pi);

    Map<String, Object> rule(@Param("id") long id);

    Map<String, Object> ruleLock(@Param("id") long id);

    Map<String, Object> usedVersionOfPi(@Param("pi") String pi);

    String minBillOfPi(@Param("pi") String pi);

    Map<String, Object> latestVersionOfPrefixPi(
            @Param("prefix") String prefix, @Param("pi") String pi);

    int clearRuleFactory(@Param("id") long id);

    List<Map<String, Object>> versionsOf(@Param("ruleId") long ruleId);

    List<Map<String, Object>> versionsByRules(@Param("ids") Collection<Long> ids);

    Map<String, Object> currentVersion(@Param("ruleId") long ruleId, @Param("version") int version);

    Map<String, Object> currentVersionLock(
            @Param("ruleId") long ruleId, @Param("version") int version);

    List<Map<String, Object>> piBindingsOf(@Param("ids") Collection<Long> ids);

    List<Map<String, Object>> listRules(
            @Param("scope") List<String> scope,
            @Param("category") String category,
            @Param("value") String value,
            @Param("like") String like);

    List<Map<String, Object>> visibleRules(@Param("scope") List<String> scope);

    boolean existsCode(@Param("code") String code);

    int insertRule(@Param("entity") RuleEntity entity);

    int insertVersion(@Param("entity") RuleVersionEntity entity);

    int nextVersion(@Param("ruleId") long ruleId);

    int updateVersionSpec(@Param("entity") RuleVersionEntity entity);

    int updateRule(
            @Param("id") long id,
            @Param("name") String name,
            @Param("scope") String scope,
            @Param("value") String value,
            @Param("version") int version,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    List<Map<String, Object>> versionsLock(@Param("ruleId") long ruleId);

    String piOfBinding(@Param("ruleId") long ruleId);

    String piOfLineRule(@Param("ruleId") long ruleId);

    int deleteVersions(@Param("ruleId") long ruleId);

    int deleteRule(@Param("id") long id);

    Map<String, Object> piRule(@Param("pi") String pi);

    Map<String, Object> piRuleLock(@Param("pi") String pi);

    int deletePiRule(@Param("pi") String pi);

    int upsertPiRule(
            @Param("pi") String pi,
            @Param("ruleId") long ruleId,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    List<Map<String, Object>> selectionsOf(@Param("bills") Collection<String> bills);

    Map<String, Object> prdLine(@Param("billNo") String billNo, @Param("seq") Integer seq);

    int upsertLineRule(
            @Param("billNo") String billNo,
            @Param("seq") int seq,
            @Param("pi") String pi,
            @Param("ruleId") Long ruleId,
            @Param("customJson") String customJson,
            @Param("by") String by,
            @Param("now") LocalDateTime now);

    long ownGenerated(@Param("billNo") String billNo, @Param("seq") int seq);

    int deleteLineRule(@Param("billNo") String billNo, @Param("seq") int seq);

    List<String> orgsOfPi(@Param("pi") String pi);

    List<String> ruleCodesLike(@Param("like") String like);

    List<Map<String, Object>> customLineRules(@Param("pi") String pi);

    int setLineRuleRule(
            @Param("ruleId") long ruleId, @Param("billNo") String billNo, @Param("seq") int seq);

    int claimVersion(@Param("id") long id, @Param("pi") String pi);

    boolean versionClaimedBy(@Param("id") long id, @Param("pi") String pi);

    Map<String, Object> version(@Param("id") long id);
}
