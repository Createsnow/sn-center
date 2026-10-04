package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** PI候选汇总；仅当前可见有效订单，历史分录累计不丢失，整数额度不抵扣其他分录。 */
@Schema(
        requiredProperties = {
            "pi",
            "bills",
            "totalQty",
            "generatedQty",
            "allocatedQty",
            "pendingQuantity",
            "quota"
        })
public record OrderPiVO(
        String pi,
        String bills,
        String totalQty,
        String generatedQty,
        String allocatedQty,
        String pendingQuantity,
        String quota) {}
