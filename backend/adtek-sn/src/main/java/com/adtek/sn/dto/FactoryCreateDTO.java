package com.adtek.sn.dto;
import jakarta.validation.constraints.*;
/** 手工建档只接受编码/名称；trim后唯一，来源和身份由服务器生成。 */
public record FactoryCreateDTO(@NotBlank @Size(max=64) String code,@NotBlank @Size(max=128) String name) {}
