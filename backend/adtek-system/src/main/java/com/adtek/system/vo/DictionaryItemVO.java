package com.adtek.system.vo;
import io.swagger.v3.oas.annotations.media.Schema;

/** 配置投影；所有 ID 和版本使用精确字符串。 */
@Schema(requiredProperties = {"id", "typeId", "value", "labelZh", "labelEn", "labelVi", "sortOrder", "enabled", "version"})
public record DictionaryItemVO(String id, String typeId, String value, String labelZh, String labelEn, String labelVi, Integer sortOrder, Boolean enabled, String version) {}
