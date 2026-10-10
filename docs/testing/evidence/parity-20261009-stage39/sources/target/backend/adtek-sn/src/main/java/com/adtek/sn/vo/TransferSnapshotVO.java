package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 页面订单快照候选；未映射工厂可回填其余字段，但不能据此提交转厂。 */
@Schema(
        requiredProperties = {
            "factoryId",
            "factoryCode",
            "factoryName",
            "orgName",
            "pi",
            "customer",
            "material",
            "materialName"
        })
public record TransferSnapshotVO(
        @Schema(nullable = true) String factoryId,
        @Schema(nullable = true) String factoryCode,
        @Schema(nullable = true) String factoryName,
        String orgName,
        String pi,
        String customer,
        String material,
        String materialName) {}
