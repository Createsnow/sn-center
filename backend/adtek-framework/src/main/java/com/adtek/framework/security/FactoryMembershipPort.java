package com.adtek.framework.security;

import java.util.Set;
import com.adtek.common.core.Actor;

/** SN拥有工厂资格关系；身份模块只通过本端口装配授权，不反向读取SN表。 */
public interface FactoryMembershipPort {
    /** 按服务器已验证主体ID读取显式工厂集合；无绑定为空，不代表全部工厂。 */
    Set<Long> factoryIds(long userId);

    /** 替换显式工厂资格。调用方须在READ_COMMITTED事务中先锁安全协调行、管理员、目标账号；
     * 本端口校验委派权限及工厂存在性，身份用例负责账号版本和同事务审计。空集合表示清除。 */
    void replace(Actor administrator, long userId, Set<Long> factoryIds);
}
