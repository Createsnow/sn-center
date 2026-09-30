package com.adtek.sn.dto;

import jakarta.validation.constraints.*;

/**
 * 显式将当前ERP目录组织关联到手工工厂；不允许把已有组织静默改派到另一工厂。
 *
 * @param orgId 配置账套中的稳定组织ID
 * @param version 本地工厂编辑版本的精确字符串
 */
public record OrganizationMapDTO(
        @NotBlank @Size(max = 64) String orgId, @NotBlank String version) {}
