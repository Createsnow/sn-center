package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 订单汇总；数量/版本/ID均精确字符串，来源内码独立于显示单号。
 *
 * @param generatedQty 来源单据所有历史分录累计，不随当前快照删除而减少
 * @param totalQty ERP精确计划量，可含小数，文本不经过浮点转换
 * @param quantityReady 当前全部分录均为整数计划量；false时仍显示原小数，但禁止SN生成
 * @param quota 整数分录各自max(0,计划量-该分录历史量)之和；quantityReady=false时整单为0
 * @param factoryId 当前显式组织映射，可空，空值不能生成或分配
 */
@Schema(
        requiredProperties = {
            "id",
            "sourceSystem",
            "sourceAccount",
            "sourceBillId",
            "billNo",
            "customer",
            "po",
            "pi",
            "orgId",
            "orgCode",
            "orgName",
            "factoryId",
            "factoryCode",
            "totalQty",
            "quantityReady",
            "lineCount",
            "statuses",
            "generatedQty",
            "allocatedQty",
            "quota",
            "snapshotVersion",
            "syncedAt"
        })
public record OrderBillVO(
        String id,
        String sourceSystem,
        String sourceAccount,
        String sourceBillId,
        String billNo,
        String customer,
        String po,
        String pi,
        String orgId,
        String orgCode,
        String orgName,
        @Schema(nullable = true) String factoryId,
        @Schema(nullable = true) String factoryCode,
        String totalQty,
        boolean quantityReady,
        String lineCount,
        String statuses,
        String generatedQty,
        String allocatedQty,
        String quota,
        String snapshotVersion,
        Instant syncedAt) {}
