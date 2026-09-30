package com.adtek.sn.vo;
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"total","transferable","toAcquire","toPrint","printed","applying","pendingAllocation"})
public record TransferCandidateVO(String total,String transferable,String toAcquire,String toPrint,String printed,String applying,String pendingAllocation) {}
