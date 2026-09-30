package com.adtek.system.vo;
import io.swagger.v3.oas.annotations.media.Schema;

/** 配置投影；所有 ID 和版本使用精确字符串。 */
@Schema(requiredProperties = {"id", "code", "name", "enabled", "version"})
public record DictionaryTypeVO(String id, String code, String name, Boolean enabled, String version) {}
