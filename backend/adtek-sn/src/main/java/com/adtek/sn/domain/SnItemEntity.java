package com.adtek.sn.domain;

import java.time.LocalDate;

/** 生成时来源不可改写；当前工厂/PI与流水归属及来源分离。历史不可解析号的seqPi/seqDec均空。 */
public record SnItemEntity(
        LocalDate genMonth,
        String id,
        String sn,
        String pi,
        String seqPi,
        Long seqDec,
        String customer,
        String material,
        Long factoryId,
        String source,
        String status,
        Long ruleVersionId,
        String sourceSystem,
        String sourceAccount,
        String sourceBillId,
        String sourceEntryId,
        Long billId,
        String billNo,
        String sourcePi,
        String sourceOrgId,
        Long sourceFactoryId,
        String taskId) {}
