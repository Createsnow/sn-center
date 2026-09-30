package com.adtek.system.dto;

import jakarta.validation.constraints.*;

import java.util.Set;

/** 工厂与动作显式授予；客户端不能声明全部工厂或任意角色。凭据由服务器生成。 */
public record ServiceClientCreateDTO(
        @NotBlank @Size(max = 100) String displayName,
        @NotEmpty @Size(max = 100) Set<@Pattern(regexp = "[1-9][0-9]{0,18}") String> factoryIds,
        @NotEmpty @Size(max = 4) Set<@NotBlank String> permissions) {}
