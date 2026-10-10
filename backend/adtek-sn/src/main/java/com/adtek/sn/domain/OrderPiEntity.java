package com.adtek.sn.domain;

import java.math.BigDecimal;

/** 已按当前order:list工厂范围聚合的PI候选，SUM使用十进制以免多个订单累计溢出long。 */
public record OrderPiEntity(
        String pi,
        long bills,
        BigDecimal totalQty,
        BigDecimal generatedQty,
        BigDecimal allocatedQty,
        BigDecimal pendingQuantity,
        BigDecimal quota,
        String customer) {}
