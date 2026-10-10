package com.adtek.sn.domain;

/** 订单快照交接目录；未映射组织保留空工厂，不含库存或订单额度。 */
public record TransferSnapshotEntity(
        Long factoryId,
        String factoryCode,
        String factoryName,
        String orgName,
        String pi,
        String customer,
        String material,
        String materialName) {}
