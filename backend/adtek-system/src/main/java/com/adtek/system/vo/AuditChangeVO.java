package com.adtek.system.vo;
import io.swagger.v3.oas.annotations.media.Schema;
/** 受控字段diff；文本值以React纯文本呈现，不当作HTML或可执行代码。 */
@Schema(requiredProperties={"field","before","after"})
public record AuditChangeVO(String field,@Schema(types={"string","null"}) String before,@Schema(types={"string","null"}) String after) {}
