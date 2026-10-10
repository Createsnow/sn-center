package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 订单汇总；数量/版本/ID均精确字符串，来源内码独立于显示单号。
 *
 * @param generatedQty 来源单据所有历史分录累计，不随当前快照删除而减少
 * @param totalQty 逐分录取整后的SN枚数；ERP原始小数仍由分录qty返回
 * @param quantityReady 数量可用标记；小数计划量按逐行取整计算，不单独阻止生成
 * @param quota 列表为max(0,匹配分录整数计划量之和-整单历史生成量)，执行时另外复核稳定来源额度
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
