package com.adtek.sn.vo;
import io.swagger.v3.oas.annotations.media.Schema;
/** ERP当前组织目录及显式映射；未映射factoryId为空。 */
@Schema(requiredProperties={"id","code","name","factoryId"})
public record OrganizationVO(String id,String code,String name,@Schema(nullable=true) String factoryId) {}
