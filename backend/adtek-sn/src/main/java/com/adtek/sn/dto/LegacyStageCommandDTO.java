package com.adtek.sn.dto;

import java.util.UUID;

/** 只导入隔离staging，不激活业务。snapshotId指受控目录；runId同时绑定来源摘要与映射版本。 */
public record LegacyStageCommandDTO(String runId,String snapshotId,String mappingVersion,boolean apply,int chunkSize) {
    public LegacyStageCommandDTO {
        if(!UUID.fromString(runId).toString().equals(runId)||!UUID.fromString(snapshotId).toString().equals(snapshotId)
                ||!"legacy-v1-staging-v1".equals(mappingVersion)||chunkSize<1||chunkSize>1000)throw new IllegalArgumentException("LEGACY_STAGE_INPUT");
    }
}
