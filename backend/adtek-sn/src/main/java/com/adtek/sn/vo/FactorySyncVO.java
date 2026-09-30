package com.adtek.sn.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 同步结果计数为精确字符串；冲突组织未自动覆盖，必须显式核对映射。 */
@Schema(requiredProperties = {"total", "inserted", "updated", "conflicts"})
public record FactorySyncVO(
        String total, String inserted, String updated, List<String> conflicts) {}
