package com.adtek.common.core;

import java.util.Set;

/** 与单个操作权限成对的范围；跨厂只读角色不能扩大另一个角色的写范围。 */
public record AuthorizationScope(
        DataScope departments, boolean allFactories, Set<Long> factoryIds) {
    public AuthorizationScope {
        if (departments == null) {
            throw new IllegalArgumentException("Department scope required");
        }
        factoryIds = Set.copyOf(factoryIds);
    }

    public boolean permitsFactory(long factoryId) {
        return allFactories || factoryIds.contains(factoryId);
    }
}
