package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 当前 SN 业务身份与可见工厂；展示角色不授予权限，各操作仍以当前授权范围为准。 */
@Schema(requiredProperties = {"role", "allFactories", "factories"})
public record SnContextVO(String role, boolean allFactories, List<FactoryVO> factories) {}
