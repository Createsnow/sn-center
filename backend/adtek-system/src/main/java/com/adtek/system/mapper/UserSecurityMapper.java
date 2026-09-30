package com.adtek.system.mapper;

import com.adtek.system.vo.MenuVO;
import com.adtek.system.vo.UserOptionsVO;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 管理用例内部策略读写；调用前必须取得协调锁并复核权限，不能直接用于对外用户查询。 */
@Mapper
public interface UserSecurityMapper {
    @Select("SELECT initialized FROM sys_security_control WHERE id=1 FOR UPDATE")
    Boolean lockControl();

    @Select("SELECT dept_name FROM sys_dept WHERE id=#{id}")
    String departmentName(@Param("id") long id);

    @Select("SELECT role_id FROM sys_user_role WHERE user_id=#{id} ORDER BY role_id")
    List<Long> roleIds(@Param("id") long id);

    @Select("SELECT id, dept_name AS name FROM sys_dept WHERE enabled=TRUE ORDER BY id")
    List<UserOptionsVO.Department> departments();

    @Select(
            "SELECT id, role_name AS name, data_scope FROM sys_role WHERE"
                    + " enabled=TRUE ORDER BY id")
    List<UserOptionsVO.Role> roles();

    @Select("SELECT COUNT(*) FROM sys_dept WHERE id=#{id} AND enabled=TRUE")
    int enabledDepartment(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM sys_role WHERE id=#{id} AND enabled=TRUE")
    int enabledRole(@Param("id") long id);

    @Delete("DELETE FROM sys_user_role WHERE user_id=#{id}")
    int clearRoles(@Param("id") long id);

    @Insert("INSERT INTO sys_user_role(user_id,role_id) VALUES(#{userId},#{roleId})")
    int addRole(@Param("userId") long userId, @Param("roleId") long roleId);

    long enabledAdministrators();

    List<MenuVO> menus(@Param("userId") long userId);
}
