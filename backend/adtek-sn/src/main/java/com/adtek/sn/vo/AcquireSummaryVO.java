package com.adtek.sn.vo;
/** ID/计数/流水统一精确字符串，未知流水为空而不是零。 */
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"factoryId","factoryCode","factoryName","pi","customer","toAcquire","toPrint","applying"})
public record AcquireSummaryVO(String factoryId,String factoryCode,String factoryName,String pi,String customer,String toAcquire,String toPrint,String applying) {}
