package com.adtek.sn.service.support;
/** 服务器规范化的持久命令；固定成员在原子执行事务内确定并立即冻结/转移。 */
public record TransferInput(long factoryId,String pi,String scope,String material,String sn,long toFactoryId,String toPi,
 String toCustomer,String toMaterial,String targetSource,String reason,String transferId,Long version,String note) {}
