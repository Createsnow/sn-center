package com.adtek.system.mapper;

import com.adtek.system.domain.SysUserEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 认证专用读写，查找范围由已验证的用户名或可信用户 ID 决定，不向普通管理查询暴露。 后续数据范围拦截器仅对这些明确登记的认证语句豁免；不能豁免整个 Mapper 包。 */
@Mapper
public interface IdentityMapper {
    SysUserEntity findByUsername(@Param("username") String username);

    SysUserEntity findById(@Param("id") long id);

    SysUserEntity lockById(@Param("id") long id);

    List<RolePermissionRow> findRolePermissions(@Param("userId") long userId);

    List<Long> findCustomDepartments(@Param("roleId") long roleId);

    List<Long> findDepartmentTree(@Param("rootId") long rootId);

    int changePassword(
            @Param("userId") long userId,
            @Param("version") long version,
            @Param("passwordHash") String passwordHash);

    /** 认证成功后的等价哈希升级；比较旧哈希/授权版本避免覆盖并发改密，不解除首次改密或改变账号状态。 */
    int upgradePasswordHash(
            @Param("userId") long userId,
            @Param("version") long version,
            @Param("previous") String previous,
            @Param("upgraded") String upgraded);

    int updateLanguage(@Param("userId") long userId, @Param("language") String language);

    /** 内部 SQL 投影，列序与 SELECT 一致；不直接作为 JSON 响应。 */
    record RolePermissionRow(
            long roleId, int dataScope, boolean allResources, String permissionCode) {}
}
