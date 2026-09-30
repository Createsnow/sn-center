package com.adtek.sn.dto;
import java.util.UUID;
/** 复用已完整STAGED的运行号；主数据映射版本固定，默认CLI不写入，不允许换源/换版本续传。 */
public record LegacyMasterCommandDTO(String runId,String snapshotId,boolean apply) {
    public LegacyMasterCommandDTO {if(!UUID.fromString(runId).toString().equals(runId)||!UUID.fromString(snapshotId).toString().equals(snapshotId))throw new IllegalArgumentException("LEGACY_ID_INVALID");}
}
