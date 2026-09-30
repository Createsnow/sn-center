package com.adtek.sn.dto;
import jakarta.validation.constraints.*;
/** 纯规则样例，不落SN/额度/流水。
 * @param start 起始十进制精确字符串；未提供默认1，0合法，负数拒绝
 */
public record RulePreviewDTO(@Size(max=128) String prefix,@Size(max=128) String suffix,
 @NotNull Integer base,@NotNull Integer length,@Size(max=144) String alphabet,String start) {}
