package com.adtek.framework.security;

import com.adtek.common.core.Actor;

import java.util.Set;

/** 可选业务资源资格扩展；各项目拥有实际关系表，身份模块通过本端口读取。 */
public interface ResourceMembershipPort {
    /** 无绑定必须返回空集合；空集合不代表全部资源。 */
    Set<Long> resourceIds(long userId);

    /** 调用方已锁安全协调行、管理员及目标账号；实现须核验委派权限和资源存在性。 */
    void replace(Actor administrator, long userId, Set<Long> resourceIds);
}
