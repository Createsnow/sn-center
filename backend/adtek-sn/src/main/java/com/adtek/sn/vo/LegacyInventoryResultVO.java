package com.adtek.sn.vo;
import java.util.List;
/** 仅库存阶段证明，不生成虚假的历史任务；原业务历史及文件须另行映射，账号保持停用。 */
public record LegacyInventoryResultVO(String runId,String state,String mappingVersion,String sourceFingerprint,String planDigest,
        String items,String keys,String counters,List<LegacySnapshotManifestVO.Issue> remainingSourceIssues,
        boolean accountsActive,boolean readyForPromotion) {}
