package com.adtek.sn.dto;

/** 内部离线原行写入命令；ordinal是固定快照顺序，key是旧复合主键摘要，payload可能含秘密不能打印。 */
public record LegacyStageRowDTO(long ordinal, String key, String payload, String digest) {
    @Override
    public String toString() {
        return "LegacyStageRowDTO[redacted]";
    }
}
