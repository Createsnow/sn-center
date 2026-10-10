package com.adtek.sn.dto;

/** 工号不可编辑；版本继续由身份模块检查。 */
public record SnAccountUpdateDTO(
        String displayName, String role, String factoryId, String version) {}
