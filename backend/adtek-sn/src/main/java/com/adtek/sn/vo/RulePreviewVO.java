package com.adtek.sn.vo;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
/** 纯计算样例，不分配流水。
 * @param maxSequence 规则容量与数据库long上限取小的精确字符串
 */
@Schema(requiredProperties={"maxSequence","samples"})
public record RulePreviewVO(String maxSequence,List<Sample> samples) {
 /** seqDecimal为精确十进制字符串；seqText按自定义字符表保留原字符。 */
 @Schema(requiredProperties={"seqDecimal","seqText","sn"})
 public record Sample(String seqDecimal,String seqText,String sn) {}
}
