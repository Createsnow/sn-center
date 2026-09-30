package com.adtek.sn.vo;

import java.util.List;

/** STAGED仅说明原行已可重复导入和核对；readyForPromotion始终false，不能当作业务切换完成。 */
public record LegacyStageResultVO(String runId,String state,boolean applied,String targetSchema,String sourceFingerprint,
        String mappingVersion,String rows,List<LegacySnapshotManifestVO.Issue> issues,boolean readyForPromotion) {}
