package com.adtek.system.mapper;

import com.adtek.system.vo.MenuVO;
import com.adtek.system.vo.UserOptionsVO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 管理用例内部策略读写；调用前必须取得协调锁并复核权限，不能直接用于对外用户查询。 */
@Mapper
public interface UserSecurityMapper {
    Boolean lockControl();

    String departmentName(@Param("id") long id);

    List<Long> roleIds(@Param("id") long id);

    List<UserOptionsVO.Department> departments();

    List<UserOptionsVO.Role> roles();

    int enabledDepartment(@Param("id") long id);

    int enabledRole(@Param("id") long id);

    int clearRoles(@Param("id") long id);

    int addRole(@Param("userId") long userId, @Param("roleId") long roleId);

    long enabledAdministrators();

    List<MenuVO> menus(@Param("userId") long userId);
}
