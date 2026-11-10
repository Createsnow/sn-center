package com.adtek.framework.security;

import java.util.List;
import java.util.Optional;

/**
 * 当前授权端口：每个已认证请求在校验 JWT 与服务端会话后调用，读取账户的当前状态与授权范围，保证停用、改密、授权变更立即生效。
 *
 * <p>权限：只由 framework 安全链调用。范围：按账户主键读取，不缓存。事务：只读、不写会话或账户表。失败：账户不存在返回空 （视为令牌无效）；
 * 数据库不可用时抛出数据访问异常（安全链返回 503）。
 */
public interface PrincipalPort {
    Optional<Snapshot> load(long userId);

    /**
     * 账户当前状态。
     *
     * @param passwordVersion 口令版本：改密 / 重置后变化，与会话签发时的版本不同即拒绝
     * @param active 启用中
     * @param interfaceOnly 只能调用对外接口（MES 接口账户），不能使用页面会话
     * @param scopeMissing 角色要求授权范围但一个也没有（不能使用任何功能）
     */
    record Snapshot(
            long userId,
            String empNo,
            String name,
            String role,
            String factoryCode,
            List<String> factories,
            long passwordVersion,
            boolean active,
            boolean interfaceOnly,
            boolean scopeMissing) {}
}
