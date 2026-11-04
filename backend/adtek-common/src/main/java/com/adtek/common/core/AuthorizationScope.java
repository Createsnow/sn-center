package com.adtek.common.core;

import java.util.Set;

/** 与单个操作权限成对的范围；跨厂只读角色不能扩大另一个角色的写范围。 */
public record AuthorizationScope(
        DataScope departments, boolean allResources, Set<Long> resourceIds) {
    public AuthorizationScope {
        if (departments == null) {
            throw new IllegalArgumentException("Department scope required");
        }
        resourceIds = Set.copyOf(resourceIds);
    }

    public boolean permitsResource(long resourceId) {
        return allResources || resourceIds.contains(resourceId);
    }
}
