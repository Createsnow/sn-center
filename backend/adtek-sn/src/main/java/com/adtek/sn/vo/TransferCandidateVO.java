package com.adtek.sn.vo;

@io.swagger.v3.oas.annotations.media.Schema(
        requiredProperties = {
            "total",
            "transferable",
            "toAcquire",
            "toPrint",
            "printed",
            "applying",
            "pendingAllocation",
            "materials",
            "duplicateCount",
            "duplicateSamples"
        })
public record TransferCandidateVO(
        String total,
        String transferable,
        String toAcquire,
        String toPrint,
        String printed,
        String applying,
        String pendingAllocation,
        java.util.List<String> materials,
        String duplicateCount,
        java.util.List<String> duplicateSamples) {}
