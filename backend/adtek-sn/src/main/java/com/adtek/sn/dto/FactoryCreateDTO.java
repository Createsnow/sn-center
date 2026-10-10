package com.adtek.sn.dto;

/** 只接收编码/名称；在业务用例修剪后按Unicode字符数校验，来源和身份由服务器生成。 */
public record FactoryCreateDTO(String code, String name) {}
