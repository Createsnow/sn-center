package com.adtek.system.mapper;

import com.adtek.system.domain.RoleEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 明确登记的全局配置 SQL，只由要求安全管理及全部部门范围的服务调用。 */
@Mapper
public interface RoleMapper {
    @Select(
            "SELECT id, role_code AS code, role_name AS name, data_scope AS dataScope,"
                    + " factory_scope AS factoryScope, enabled,"
                    + " version FROM sys_role ORDER BY id LIMIT"
                    + " 1001")
    List<RoleEntity> list();

    @Select(
            "SELECT id, role_code AS code, role_name AS name, data_scope AS dataScope,"
                    + " factory_scope AS factoryScope, enabled, version"
                    + " FROM sys_role WHERE id=#{id}")
    RoleEntity find(@Param("id") long id);

    @Insert(
            "INSERT INTO sys_role(role_code,role_name,data_scope,factory_scope,enabled) VALUES"
                    + " (#{code},#{name},#{dataScope},#{factoryScope},#{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RoleEntity entity);

    @Update(
            "UPDATE sys_role SET"
                    + " role_code=#{code},role_name=#{name},data_scope=#{dataScope},"
                    + "factory_scope=#{factoryScope},enabled=#{enabled},version=version+1"
                    + " WHERE id=#{id} AND version=#{version}")
    int update(RoleEntity entity);
}
