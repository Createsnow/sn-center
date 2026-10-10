package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 当前生成条件控制预演；PI汇总按当前组织和历史生成身份合并，线表和历史另有分页。 */
@Schema(
        requiredProperties = {
            "bill",
            "factoryName",
            "counter",
            "rule",
            "pendingQuantity",
            "issues",
            "piSummary",
            "jobs",
            "allocations",
            "maxBatchQuantity",
            "ruleCode",
            "ruleScope",
            "ruleBinding",
            "ruleSource",
            "ruleChoiceRequired",
            "nextStart"
        })
public record GenerationContextVO(
        OrderBillVO bill,
        String factoryName,
        PiCounterVO counter,
        @Schema(nullable = true) RuleVersionVO rule,
        String pendingQuantity,
        List<String> issues,
        List<Summary> piSummary,
        List<SnWorkVO> jobs,
        List<SnWorkVO> allocations,
        String maxBatchQuantity,
        String ruleCode,
        String ruleScope,
        String ruleBinding,
        @Schema(allowableValues = {"NONE", "PI", "CUSTOMER", "CHOSEN"}) String ruleSource,
        boolean ruleChoiceRequired,
        @Schema(nullable = true) NextSequenceVO nextStart) {
    @Schema(
            requiredProperties = {
                "billId",
                "billNo",
                "inSnapshot",
                "totalQty",
                "generatedQty",
                "allocatedQty",
                "quota"
            })
    public record Summary(
            String billId,
            String billNo,
            boolean inSnapshot,
            String totalQty,
            String generatedQty,
            String allocatedQty,
            String quota) {}
}
