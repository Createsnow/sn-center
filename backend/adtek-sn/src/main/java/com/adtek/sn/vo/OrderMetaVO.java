package com.adtek.sn.vo;
import java.time.Instant;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
/** 全部统计和未映射列表都在当前order:list工厂范围内；没有历史同步时syncedAt为空。 */
@Schema(requiredProperties={"syncedAt","bills","rows","excludedEmptyCustomer","unmappedOrganizations"})
public record OrderMetaVO(@Schema(nullable=true) Instant syncedAt,String bills,String rows,String excludedEmptyCustomer,List<String> unmappedOrganizations) {}
