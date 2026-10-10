package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"id"})
public record SnAccountSavedVO(String id) {}
