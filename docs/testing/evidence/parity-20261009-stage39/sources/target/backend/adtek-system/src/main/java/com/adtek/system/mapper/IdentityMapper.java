package com.adtek.system.mapper;

import com.adtek.system.domain.SysUserEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 认证专用读写，查找范围由已验证的用户名或可信用户 ID 决定，不向普通管理查询暴露。 后续数据范围拦截器仅对这些明确登记的认证语句豁免；不能豁免整个 Mapper 包。 */
@Mapper
public interface IdentityMapper {
    /** 与登录审计同事务；条件更新拒绝并发停用/改密后的旧认证结果。 */
    @Update(
            """
            UPDATE sys_user SET last_login_at=UTC_TIMESTAMP(6)
            WHERE id=#{id} AND security_version=#{version}
              AND enabled=TRUE AND principal_type='USER'
            """)
    int recordLogin(@Param("id") long id, @Param("version") long version);

    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    SysUserEntity findByUsername(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE id = #{id}")
    SysUserEntity findById(@Param("id") long id);

    @Select("SELECT * FROM sys_user WHERE id = #{id} FOR UPDATE")
    SysUserEntity lockById(@Param("id") long id);

    @Select(
            "SELECT r.id AS role_id, r.data_scope,"
                    + " (r.factory_scope = 2) AS all_factories, m.permission_code\n"
                    + "FROM sys_user_role ur\n"
                    + "JOIN sys_role r ON r.id = ur.role_id AND r.enabled = TRUE\n"
                    + "JOIN sys_role_menu rm ON rm.role_id = r.id\n"
                    + "JOIN sys_menu m ON m.id = rm.menu_id AND m.enabled = TRUE\n"
                    + "WHERE ur.user_id = #{userId} AND m.permission_code IS NOT NULL\n"
                    + "ORDER BY r.id, m.id\n")
    List<RolePermissionRow> findRolePermissions(@Param("userId") long userId);

    @Select(
            "SELECT rd.dept_id FROM sys_role_dept rd JOIN sys_dept d ON d.id=rd.dept_id AND"
                    + " d.enabled=TRUE WHERE rd.role_id = #{roleId} ORDER BY rd.dept_id")
    List<Long> findCustomDepartments(@Param("roleId") long roleId);

    @Select(
            "WITH RECURSIVE descendants AS (\n"
                    + "    SELECT id FROM sys_dept WHERE id = #{rootId} AND enabled = TRUE\n"
                    + "    UNION ALL\n"
                    + "    SELECT d.id FROM sys_dept d JOIN descendants p ON d.parent_id ="
                    + " p.id WHERE d.enabled = TRUE\n"
                    + ") SELECT id FROM descendants\n")
    List<Long> findDepartmentTree(@Param("rootId") long rootId);

    @Update(
            """
            UPDATE sys_user SET password_hash=#{passwordHash}, must_change_password=FALSE,
              security_version=security_version+1, update_by=#{userId}, update_time=UTC_TIMESTAMP(6)
            WHERE id=#{userId} AND security_version=#{version}
            """)
    int changePassword(
            @Param("userId") long userId,
            @Param("version") long version,
            @Param("passwordHash") String passwordHash);

    /** 认证成功后的等价哈希升级；比较旧哈希/授权版本避免覆盖并发改密，不解除首次改密或改变账号状态。 */
    @Update(
            """
            UPDATE sys_user SET password_hash=#{upgraded},update_time=UTC_TIMESTAMP(6)
            WHERE id=#{userId} AND security_version=#{version} AND password_hash=#{previous}
              AND enabled=TRUE AND principal_type='USER'
            """)
    int upgradePasswordHash(
            @Param("userId") long userId,
            @Param("version") long version,
            @Param("previous") String previous,
            @Param("upgraded") String upgraded);

    @Update(
            "UPDATE sys_user SET language=#{language}, update_by=#{userId},"
                    + " update_time=UTC_TIMESTAMP(6) WHERE id=#{userId}")
    int updateLanguage(@Param("userId") long userId, @Param("language") String language);

    /** 内部 SQL 投影，列序与 SELECT 一致；不直接作为 JSON 响应。 */
    record RolePermissionRow(
            long roleId, int dataScope, boolean allFactories, String permissionCode) {}
}
