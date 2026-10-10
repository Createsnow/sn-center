package com.adtek.sn.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** ERP明细保留精确十进制；SN枚数遵循原型，逐分录取整数部分后再汇总。 */
public final class PlannedQuantity {
    private PlannedQuantity() {}

    /** 匹配DECIMAL(23,4)与既有long额度上界；过精度/负数/超界全部拒绝，不依赖MySQL隐式舍入。 */
    public static BigDecimal require(BigDecimal value) {
        if (value == null
                || value.signum() < 0
                || value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0)
            throw new ArithmeticException("Invalid planned quantity");
        return value.setScale(4, RoundingMode.UNNECESSARY).stripTrailingZeros();
    }

    public static long snCount(BigDecimal value) {
        return require(value).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /** 先对本分录取整，再扣该稳定来源分录已生成量；不将多个分录的小数凑成一枚。 */
    public static long remaining(BigDecimal value, long generated) {
        return Math.max(0, snCount(value) - generated);
    }

    public static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
