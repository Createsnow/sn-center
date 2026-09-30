package com.adtek.sn.vo;
import java.util.List;
/** 主数据阶段摘要没有口令哈希/原用户数据；readyForPromotion与accountsActive始终false。 */
public record LegacyMasterResultVO(String runId,String state,String mappingVersion,String sourceFingerprint,String planDigest,
        String factories,String users,String rules,String ruleVersions,String unknownActorReferences,
        List<LegacySnapshotManifestVO.Issue> remainingSourceIssues,boolean accountsActive,boolean readyForPromotion) {}
