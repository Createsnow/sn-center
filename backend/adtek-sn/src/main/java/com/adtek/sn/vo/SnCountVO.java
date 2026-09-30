package com.adtek.sn.vo;
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"name","quantity"})
public record SnCountVO(String name,String quantity) {}
