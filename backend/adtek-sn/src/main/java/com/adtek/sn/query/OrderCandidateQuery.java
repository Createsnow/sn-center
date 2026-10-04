package com.adtek.sn.query;

import java.util.Set;

/** 服务装配的订单查询；工厂资格来自当前可信主体，不能从HTTP绑定。 */
public record OrderCandidateQuery(
        String like,
        String customer,
        String pi,
        Long factoryId,
        boolean pending,
        boolean allFactories,
        Set<Long> factoryIds,
        int offset,
        int limit) {}
