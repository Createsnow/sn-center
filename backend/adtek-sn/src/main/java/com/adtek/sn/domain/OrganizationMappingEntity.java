package com.adtek.sn.domain;
/** 外部稳定组织到本地工厂的不可隐式改派映射；orgName只是当前展示值。 */
public record OrganizationMappingEntity(String sourceSystem,String sourceAccount,String orgId,String orgCode,String orgName,long factoryId) {}
