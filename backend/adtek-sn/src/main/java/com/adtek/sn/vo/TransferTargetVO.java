package com.adtek.sn.vo;
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"factoryId","factoryCode","factoryName","pi","customer","material"})
public record TransferTargetVO(String factoryId,String factoryCode,String factoryName,String pi,String customer,String material) {}
