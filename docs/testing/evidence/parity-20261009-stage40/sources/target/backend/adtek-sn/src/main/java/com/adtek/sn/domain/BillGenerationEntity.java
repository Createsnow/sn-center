package com.adtek.sn.domain;

/** 整单历史身份；首次 PI/客户固定，工厂取最后一次成功生成，数量仍在来源额度台账。 */
public record BillGenerationEntity(
        long billId,
        String firstPi,
        String firstCustomer,
        long factoryId,
        String firstTaskId,
        String lastTaskId) {}
