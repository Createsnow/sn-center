package com.adtek.common.core;

import java.util.Map;
import java.util.Set;

/**
 * 可信认证过程装配的当前授权，不用于接收请求体或 Actor header。
 * 每个权限单独绑定部门/工厂范围，不能先扁平合并角色范围再套到所有权限。
 */
public record Actor(long userId, long securityVersion, boolean mustChangePassword,
                    Map<String, AuthorizationScope> grants, String principalType) {
    public Actor(long userId,long securityVersion,boolean mustChangePassword,Map<String,AuthorizationScope> grants) {
        this(userId,securityVersion,mustChangePassword,grants,"USER");
    }
    public Actor {
        if (userId <= 0 || securityVersion <= 0 || grants == null) {
            throw new IllegalArgumentException("Invalid trusted actor");
        }
        if (!Set.of("USER","SERVICE").contains(principalType)) { throw new IllegalArgumentException("Invalid principal type"); }
        grants = Map.copyOf(grants);
    }

    public Set<String> permissions() {
        return grants.keySet();
    }
}
