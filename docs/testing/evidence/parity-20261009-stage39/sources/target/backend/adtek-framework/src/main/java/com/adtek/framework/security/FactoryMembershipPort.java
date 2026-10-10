package com.adtek.framework.security;

import com.adtek.common.core.Actor;

import java.util.List;
import java.util.Set;

/** SN拥有工厂资格关系；身份模块只通过本端口装配授权，不反向读取SN表。 */
public interface FactoryMembershipPort {
    /** 按服务器已验证主体ID读取显式工厂集合；无绑定为空，不代表全部工厂。 */
    Set<Long> factoryIds(long userId);

    /** 对已通过账号可见性检查的主体装配工厂标签，不返回工厂秘密或账号凭据。 */
    List<Factory> details(long userId);

    /** 账号列表的工厂交集条件；调用方仍须在分页 SQL 中应用账号数据范围。 */
    Set<Long> members(Actor reader, long factoryId);

    @io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"id", "name", "code"})
    record Factory(String id, String name, String code) {}

    /**
     * 替换显式工厂资格。调用方须在READ_COMMITTED事务中先锁安全协调行、管理员、目标账号； 本端口校验委派权限及工厂存在性，身份用例负责账号版本和同事务审计。空集合表示清除。
     */
    void replace(Actor administrator, long userId, Set<Long> factoryIds);
}
