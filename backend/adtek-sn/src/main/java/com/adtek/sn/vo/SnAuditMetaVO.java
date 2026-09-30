package com.adtek.sn.vo;
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"permanent","exportLimit","defaultDays","archivesQueryable"})
public record SnAuditMetaVO(boolean permanent,String exportLimit,int defaultDays,boolean archivesQueryable) {}
