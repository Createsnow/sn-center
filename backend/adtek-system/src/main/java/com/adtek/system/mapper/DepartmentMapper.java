package com.adtek.system.mapper;

import com.adtek.system.domain.DepartmentEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 明确登记的全局配置 SQL，只由要求安全管理及全部部门范围的服务调用。 */
@Mapper
public interface DepartmentMapper {
    @Select(
            "SELECT id, dept_name AS name, parent_id AS parentId, enabled, version FROM sys_dept"
                    + " ORDER BY id LIMIT 1001")
    List<DepartmentEntity> list();

    @Select(
            "SELECT id, dept_name AS name, parent_id AS parentId, enabled, version FROM sys_dept"
                    + " WHERE id=#{id}")
    DepartmentEntity find(@Param("id") long id);

    @Insert(
            "INSERT INTO sys_dept(dept_name,parent_id,enabled) VALUES"
                    + " (#{name},#{parentId},#{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DepartmentEntity entity);

    @Update(
            "UPDATE sys_dept SET"
                    + " dept_name=#{name},parent_id=#{parentId},"
                    + "enabled=#{enabled},version=version+1"
                    + " WHERE id=#{id} AND version=#{version}")
    int update(DepartmentEntity entity);
}
