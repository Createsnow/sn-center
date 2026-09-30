package com.adtek.system.vo;
import io.swagger.v3.oas.annotations.media.Schema;
/** 自身授权版本变化时提示客户端结束旧会话。 */
@Schema(requiredProperties = {"id", "reauthenticate"})
public record ConfigurationChangeVO(String id, boolean reauthenticate) {}
