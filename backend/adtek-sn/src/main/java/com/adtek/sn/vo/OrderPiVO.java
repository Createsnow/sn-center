package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** PI候选汇总；仅当前可见有效订单，按整单历史累计计算余额后再汇总，不跨订单抵扣。 */
@Schema(
        requiredProperties = {
            "pi",
            "bills",
            "totalQty",
            "generatedQty",
            "allocatedQty",
            "pendingQuantity",
            "quota",
            "customer"
        })
public record OrderPiVO(
        String pi,
        String bills,
        String totalQty,
        String generatedQty,
        String allocatedQty,
        String pendingQuantity,
        String quota,
        String customer) {}
