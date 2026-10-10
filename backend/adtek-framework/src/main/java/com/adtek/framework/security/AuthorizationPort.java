package com.adtek.framework.security;

import com.adtek.common.core.Actor;

import java.util.Optional;

/** 身份模块提供当前权限；framework 不读取身份业务表。 */
public interface AuthorizationPort {
    /**
     * 根据已验签令牌中的用户 ID 重新加载当前账号、权限、部门及工厂范围。 停用、不存在返回空；角色/绑定修改必须推进对应用户 securityVersion；撤销登录时另推进
     * credentialVersion。 依赖故障必须传播，调用方返回 503，不复用过期权限放行。
     */
    Optional<Actor> loadCurrent(long userId);

    /** 会话入口先核对凭据版本，再区分暂时停用；过期凭据不得泄露当前账户状态。 */
    default Optional<Actor> loadForSession(long userId, long credentialVersion) {
        return loadCurrent(userId).filter(actor -> actor.credentialVersion() == credentialVersion);
    }
}
