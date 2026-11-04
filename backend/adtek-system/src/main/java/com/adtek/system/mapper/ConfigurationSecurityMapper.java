package com.adtek.system.mapper;

import com.adtek.system.domain.SysUserEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 仅在安全协调锁内读取关联与撤销授权；SQL 标识逐个登记，不开放通用表名参数。 */
@Mapper
public interface ConfigurationSecurityMapper {
    List<SysUserEntity> lockUsers();

    List<Long> roleMenus(@Param("id") long id);

    List<Long> roleDepartments(@Param("id") long id);

    List<Long> roleUsers(@Param("id") long id);

    List<Long> menuUsers(@Param("id") long id);

    int clearMenus(@Param("id") long id);

    int clearDepartments(@Param("id") long id);

    int addMenu(@Param("id") long id, @Param("menuId") long menuId);

    int addDepartment(@Param("id") long id, @Param("deptId") long deptId);

    int invalidate(@Param("id") long id, @Param("actorId") long actorId);

    long enabledDepartmentUsers(@Param("id") long id);
}
