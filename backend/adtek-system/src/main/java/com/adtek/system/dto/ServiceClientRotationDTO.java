package com.adtek.system.dto;

import jakarta.validation.constraints.*;

/** 轮换使用授权版本进行并发控制；新秘密由服务器产生。 */
public record ServiceClientRotationDTO(
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String version) {}
