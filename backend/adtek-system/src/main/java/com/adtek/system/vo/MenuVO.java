package com.adtek.system.vo;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(requiredProperties = {"id", "name", "path", "component", "permission"})
public record MenuVO(String id, String name, String path, String component, String permission) {}
