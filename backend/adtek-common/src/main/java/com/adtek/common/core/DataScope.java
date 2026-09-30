package com.adtek.common.core;

import java.util.Set;

/** 已从权威角色数据解析的部门范围；空集合且非 all/self 表示无可见对象。 */
public record DataScope(boolean all, Set<Long> departmentIds, boolean self) {
    public DataScope {
        departmentIds = Set.copyOf(departmentIds);
    }

    public boolean permits(long actorId, long objectOwnerId, Long objectDepartmentId) {
        return all
                || (self && actorId == objectOwnerId)
                || (objectDepartmentId != null && departmentIds.contains(objectDepartmentId));
    }
}
