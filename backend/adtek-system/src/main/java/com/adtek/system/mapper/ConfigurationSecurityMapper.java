package com.adtek.system.mapper;
import com.adtek.system.domain.SysUserEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 仅在安全协调锁内读取关联与撤销授权；SQL 标识逐个登记，不开放通用表名参数。 */
@Mapper
public interface ConfigurationSecurityMapper {
    @Select("SELECT id,dept_id,security_version,enabled FROM sys_user ORDER BY id FOR UPDATE")
    List<SysUserEntity> lockUsers();
    @Select("SELECT menu_id FROM sys_role_menu WHERE role_id=#{id} ORDER BY menu_id")
    List<Long> roleMenus(@Param("id") long id);
    @Select("SELECT dept_id FROM sys_role_dept WHERE role_id=#{id} ORDER BY dept_id")
    List<Long> roleDepartments(@Param("id") long id);
    @Select("SELECT user_id FROM sys_user_role WHERE role_id=#{id} ORDER BY user_id")
    List<Long> roleUsers(@Param("id") long id);
    @Select("SELECT DISTINCT ur.user_id FROM sys_user_role ur JOIN sys_role_menu rm ON rm.role_id=ur.role_id WHERE rm.menu_id=#{id}")
    List<Long> menuUsers(@Param("id") long id);
    @Delete("DELETE FROM sys_role_menu WHERE role_id=#{id}")
    int clearMenus(@Param("id") long id);
    @Delete("DELETE FROM sys_role_dept WHERE role_id=#{id}")
    int clearDepartments(@Param("id") long id);
    @Insert("INSERT INTO sys_role_menu(role_id,menu_id) VALUES (#{id},#{menuId})")
    int addMenu(@Param("id") long id, @Param("menuId") long menuId);
    @Insert("INSERT INTO sys_role_dept(role_id,dept_id) VALUES (#{id},#{deptId})")
    int addDepartment(@Param("id") long id, @Param("deptId") long deptId);
    @Update("UPDATE sys_user SET security_version=security_version+1,update_by=#{actorId},update_time=UTC_TIMESTAMP(6) WHERE id=#{id}")
    int invalidate(@Param("id") long id, @Param("actorId") long actorId);
    @Select("SELECT COUNT(*) FROM sys_user WHERE dept_id=#{id} AND enabled=TRUE")
    long enabledDepartmentUsers(@Param("id") long id);
}
