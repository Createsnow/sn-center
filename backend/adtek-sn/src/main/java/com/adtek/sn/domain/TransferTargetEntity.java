package com.adtek.sn.domain;

/** 仅交接目录字段，不含数量、来源账套、规则或其他工厂库存。 */
public record TransferTargetEntity(
        long factoryId,
        String factoryCode,
        String factoryName,
        String pi,
        String customer,
        String material) {}
