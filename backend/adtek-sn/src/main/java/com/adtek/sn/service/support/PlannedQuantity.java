package com.adtek.sn.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** ERP计划量保留精确十进制；与离散SN额度分开。小数单位未确认前，不对其舍入、截断或分配枚数。 */
public final class PlannedQuantity {
 private PlannedQuantity(){}
 /** 匹配DECIMAL(23,4)与既有long额度上界；过精度/负数/超界全部拒绝，不依赖MySQL隐式舍入。 */
 public static BigDecimal require(BigDecimal value){
  if(value==null||value.signum()<0||value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE))>0)throw new ArithmeticException("Invalid planned quantity");
  return value.setScale(4,RoundingMode.UNNECESSARY).stripTrailingZeros();
 }
 public static boolean integral(BigDecimal value){return value!=null&&value.stripTrailingZeros().scale()<=0;}
 /** 小数没有可用SN额度；原小数仍由qty独立展示，所属单据另明确quantityReady=false及阻止原因。 */
 public static long remaining(BigDecimal value,long generated){return integral(value)?Math.max(0,value.longValueExact()-generated):0;}
 public static String text(BigDecimal value){return value==null?null:value.stripTrailingZeros().toPlainString();}
}
